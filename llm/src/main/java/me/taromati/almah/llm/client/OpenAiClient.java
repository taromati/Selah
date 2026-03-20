package me.taromati.almah.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.config.LlmConfigProperties;
import me.taromati.almah.llm.util.LlmResponseSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Generic OpenAI-compatible HTTP 클라이언트.
 * vLLM, ollama 등 OpenAI 호환 API를 사용하는 프로바이더의 공통 기반이자,
 * OpenAI 공식 API의 직접 프로바이더로도 사용됩니다.
 *
 * <p>LlmClient를 구현하여 직접 빈으로 등록 가능합니다.
 * 서브클래스(VllmClient 등)는 필요한 메서드만 override합니다.</p>
 */
@Slf4j
public class OpenAiClient implements LlmClient {

    protected final String baseUrl;
    protected final String model;
    protected final String apiKey;
    protected final String providerLabel;
    protected final RestTemplate restTemplate;
    protected final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final LlmConfigProperties.ProviderConfig providerConfig;

    @Autowired(required = false)
    protected LlmAlertCallback alertCallback;

    protected LlmRateLimiter rateLimiter;

    public void setRateLimiter(LlmRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public void setAlertCallback(LlmAlertCallback alertCallback) {
        this.alertCallback = alertCallback;
    }

    /**
     * 기존 서브클래스(VllmClient 등) 호환 생성자.
     */
    protected OpenAiClient(String baseUrl, String model, String apiKey, String providerLabel,
                           int connectTimeoutSeconds, int readTimeoutSeconds) {
        this(baseUrl, model, apiKey, providerLabel, connectTimeoutSeconds, readTimeoutSeconds, null);
    }

    /**
     * ProviderConfig 포함 생성자 — LlmClient로 직접 사용 시.
     */
    public OpenAiClient(String baseUrl, String model, String apiKey, String providerLabel,
                        int connectTimeoutSeconds, int readTimeoutSeconds,
                        LlmConfigProperties.ProviderConfig providerConfig) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "프로바이더 '" + providerLabel + "'에 model이 지정되지 않았습니다. config.yml에 model을 설정하세요.");
        }
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.providerLabel = providerLabel;
        this.providerConfig = providerConfig;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restTemplate = new RestTemplate(factory);
    }

    // ─── LlmClient 인터페이스 ───

    @Override
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params,
                                                  ChatCompletionRequest.ToolConfig toolConfig, String model) {
        warnIfInsideTransaction();

        // OpenAI API 미지원 파라미터 필터링 (minP, repetitionPenalty)
        SamplingParams filtered = new SamplingParams(
                params.maxTokens(),
                params.temperature(),
                params.topP(),
                null,  // minP: OpenAI 미지원
                params.frequencyPenalty(),
                null,  // repetitionPenalty: OpenAI 미지원
                params.presencePenalty()
        );

        // model 파라미터 지원 (세션별 모델 오버라이드)
        if (model != null) {
            ChatCompletionRequest request = buildRequest(messages, filtered, toolConfig);
            request.setModel(model);
            return doApiCallWithRequest(request);
        }

        return doApiCall(messages, filtered, toolConfig);
    }

    @Override
    public String getProviderName() {
        return providerLabel != null ? providerLabel : "openai";
    }

    @Override
    public String getDefaultModel() {
        return this.model;
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        if (providerConfig == null) return ProviderCapabilities.empty();
        return new ProviderCapabilities(
                providerConfig.getContextWindow(),
                providerConfig.getMaxTokens(),
                providerConfig.getCharsPerToken(),
                providerConfig.getRecentKeep()
        );
    }

    // ─── 편의 오버로드 (AiChat 등 기존 호출자용) ───

    /**
     * temperature만 지정하는 편의 오버로드 (요약, 타이밍 판단 등)
     */
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, Double temperature) {
        return chatCompletion(messages, SamplingParams.withTemperature(temperature), null);
    }

    /**
     * SamplingParams 기반 호출 (도구 없음)
     */
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params) {
        return chatCompletion(messages, params, null);
    }

    /**
     * SamplingParams + ToolConfig 기반 호출 (핵심 메서드).
     * 서브클래스에서 override하여 프로바이더 특화 로직(cap, retry 등)을 추가합니다.
     */
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params,
                                                  ChatCompletionRequest.ToolConfig toolConfig) {
        warnIfInsideTransaction();
        return doApiCall(messages, params, toolConfig);
    }

    // ─── 내부 구현 ───

    /**
     * Generic HTTP 호출 (retry 없음). 서브클래스에서 확장 가능.
     */
    protected ChatCompletionResponse doApiCall(List<ChatMessage> messages, SamplingParams params,
                                                ChatCompletionRequest.ToolConfig toolConfig) {
        if (rateLimiter != null) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenAiClientException("Rate limiter interrupted", e);
            }
        }

        String url = baseUrl + "/chat/completions";
        ChatCompletionRequest request = buildRequest(messages, params, toolConfig);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            if (apiKey != null && !apiKey.isEmpty()) {
                headers.setBearerAuth(apiKey);
            }

            HttpEntity<ChatCompletionRequest> httpEntity = new HttpEntity<>(request, headers);

            log.debug("[OpenAiClient] Request URL: {}", url);
            log.debug("[OpenAiClient] Request Body: {}", request);

            ResponseEntity<ChatCompletionResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    httpEntity,
                    ChatCompletionResponse.class
            );

            log.debug("[OpenAiClient] Response Status: {}", response.getStatusCode());
            log.debug("[OpenAiClient] Response Body: {}", response.getBody());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                consecutiveFailures.set(0);
                LlmResponseSanitizer.sanitize(response.getBody());
                return response.getBody();
            } else {
                handleFailure("API 응답 오류: " + response.getStatusCode(), null);
                throw new OpenAiClientException("API request failed with status " + response.getStatusCode());
            }
        } catch (OpenAiClientException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            handleFailure("API 오류: " + e.getMessage(), e);
            throw new OpenAiClientException("Failed to call OpenAI API: " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            String errorMsg = providerLabel + " 서버 연결 실패: " + e.getMessage();
            handleFailure(errorMsg, e);
            throw new OpenAiClientException(errorMsg, e);
        } catch (Exception e) {
            log.error("[OpenAiClient] Error: {}", e.getMessage(), e);
            handleFailure("API 호출 오류: " + e.getMessage(), e);
            throw new OpenAiClientException("Failed to call OpenAI API: " + e.getMessage(), e);
        }
    }

    /**
     * 이미 빌드된 request 객체로 API 호출 (모델 오버라이드 등)
     */
    private ChatCompletionResponse doApiCallWithRequest(ChatCompletionRequest request) {
        String url = baseUrl + "/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.setBearerAuth(apiKey);
            }

            var httpEntity = new HttpEntity<>(request, headers);
            var response = restTemplate.exchange(
                    url, HttpMethod.POST, httpEntity, ChatCompletionResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                consecutiveFailures.set(0);
                LlmResponseSanitizer.sanitize(response.getBody());
                return response.getBody();
            } else {
                handleFailure("API 응답 오류: " + response.getStatusCode(), null);
                throw new OpenAiClientException("OpenAI API failed with status " + response.getStatusCode());
            }
        } catch (OpenAiClientException e) {
            throw e;
        } catch (Exception e) {
            handleFailure("OpenAI API 호출 오류: " + e.getMessage(), e);
            throw new OpenAiClientException("Failed to call OpenAI API: " + e.getMessage(), e);
        }
    }

    protected ChatCompletionRequest buildRequest(List<ChatMessage> messages, SamplingParams params,
                                                   ChatCompletionRequest.ToolConfig toolConfig) {
        ChatCompletionRequest.ChatCompletionRequestBuilder builder = ChatCompletionRequest.builder()
                .model(this.model)
                .messages(messages)
                .maxTokens(params.maxTokens())
                .temperature(params.temperature())
                .topP(params.topP())
                .minP(params.minP())
                .frequencyPenalty(params.frequencyPenalty())
                .repetitionPenalty(params.repetitionPenalty())
                .presencePenalty(params.presencePenalty());

        if (toolConfig != null && toolConfig.getTools() != null && !toolConfig.getTools().isEmpty()) {
            builder.tools(toolConfig.getTools());
            builder.toolChoice(toolConfig.getToolChoice());
        }

        return builder.build();
    }

    /**
     * TX 내부 LLM 호출 감지 — DB 잠금 장시간 점유 방지 가드
     */
    protected void warnIfInsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            String callerInfo = getCallerInfo();
            log.error("[OpenAiClient] TX 내부 LLM 호출 감지! Caller: {}", callerInfo);
            if (alertCallback != null) {
                alertCallback.sendCriticalAlert("LLM_INSIDE_TX",
                        "TX 내부 LLM 호출 감지",
                        "호출 위치: " + callerInfo);
            }
        }
    }

    private String getCallerInfo() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className.startsWith("me.taromati.almah")
                    && !className.contains("OpenAiClient")
                    && !className.contains("VllmClient")
                    && !className.contains("$$")) {
                return className + "." + element.getMethodName() + ":" + element.getLineNumber();
            }
        }
        return "unknown";
    }

    /**
     * SSE 스트리밍 HTTP 호출 — 토큰 단위 콜백.
     * TtsClient의 스트리밍 패턴과 동일하게 RestTemplate.execute() + InputStream 사용.
     */
    protected ChatCompletionResponse doApiCallStream(List<ChatMessage> messages, SamplingParams params,
                                                       ChatCompletionRequest.ToolConfig toolConfig,
                                                       Consumer<String> tokenCallback) {
        if (rateLimiter != null) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenAiClientException("Rate limiter interrupted", e);
            }
        }

        String url = baseUrl + "/chat/completions";
        ChatCompletionRequest request = buildRequest(messages, params, toolConfig);
        request.setStream(true);

        try {
            ChatCompletionResponse response = restTemplate.execute(url, HttpMethod.POST,
                    req -> {
                        req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        req.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                        if (apiKey != null && !apiKey.isEmpty()) {
                            req.getHeaders().setBearerAuth(apiKey);
                        }
                        objectMapper.writeValue(req.getBody(), request);
                    },
                    resp -> {
                        if (!resp.getStatusCode().is2xxSuccessful()) {
                            throw new OpenAiClientException("Streaming API 응답 오류: " + resp.getStatusCode());
                        }
                        return SseStreamParser.parseStream(resp.getBody(), tokenCallback, objectMapper);
                    });

            consecutiveFailures.set(0);
            if (response != null) {
                LlmResponseSanitizer.sanitize(response);
            }
            return response;
        } catch (OpenAiClientException e) {
            handleFailure("Streaming API 오류: " + e.getMessage(), e);
            throw e;
        } catch (ResourceAccessException e) {
            String errorMsg = providerLabel + " 서버 연결 실패 (streaming): " + e.getMessage();
            handleFailure(errorMsg, e);
            throw new OpenAiClientException(errorMsg, e);
        } catch (Exception e) {
            log.error("[OpenAiClient] Streaming error: {}", e.getMessage(), e);
            handleFailure("Streaming API 호출 오류: " + e.getMessage(), e);
            throw new OpenAiClientException("Failed to call streaming API: " + e.getMessage(), e);
        }
    }

    /**
     * 실패 처리 및 연속 실패 시 알림
     */
    protected void handleFailure(String errorMessage, Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        log.error("[OpenAiClient] Failure #{}: {}", failures, errorMessage);

        if (failures == LlmConstants.CONSECUTIVE_FAILURE_ALERT_THRESHOLD && alertCallback != null) {
            alertCallback.sendCriticalAlert("VLLM_CONSECUTIVE_FAILURES",
                    providerLabel + " API 연속 실패",
                    "연속 실패 횟수: " + failures + "\n" +
                    "서버 URL: " + baseUrl + "\n" +
                    "마지막 에러: " + errorMessage);
        }
    }
}
