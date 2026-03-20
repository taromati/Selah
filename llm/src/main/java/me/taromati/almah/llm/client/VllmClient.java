package me.taromati.almah.llm.client;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.config.LlmConfigProperties;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vLLM 전용 LLM 클라이언트.
 * OpenAiClient를 상속하여 HTTP 호출 로직을 재사용하고,
 * vLLM 특화 로직(max_tokens cap, 400 에러 재시도)을 캡슐화합니다.
 */
@Slf4j
public class VllmClient extends OpenAiClient implements LlmClient {

    private static final int MIN_MAX_TOKENS = 128;
    private static final int CHARS_PER_TOKEN_ESTIMATE = 2;
    private static final int TOKEN_OVERHEAD = 50;
    private static final Pattern MAX_TOKENS_ERROR_PATTERN =
            Pattern.compile("maximum context length is (\\d+) tokens.*has (\\d+) input tokens");

    private final LlmConfigProperties.ProviderConfig providerConfig;

    public VllmClient(String baseUrl, String model, String apiKey, String providerLabel,
                      int connectTimeoutSeconds, int readTimeoutSeconds,
                      LlmConfigProperties.ProviderConfig providerConfig) {
        super(baseUrl,
              model,
              apiKey,
              providerLabel,
              connectTimeoutSeconds,
              readTimeoutSeconds,
              providerConfig);
        this.providerConfig = providerConfig;
    }

    // ─── LlmClient 인터페이스 ───

    @Override
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params,
                                                   ChatCompletionRequest.ToolConfig toolConfig, String model) {
        // vLLM은 config 모델 고정 (model 파라미터 무시)
        return chatCompletion(messages, params, toolConfig);
    }

    @Override
    public String getProviderName() {
        return providerLabel;
    }

    @Override
    public String getDefaultModel() {
        return providerConfig.getModel();
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return new ProviderCapabilities(
                providerConfig.getContextWindow(),
                providerConfig.getMaxTokens(),
                providerConfig.getCharsPerToken(),
                providerConfig.getRecentKeep()
        );
    }

    // ─── LlmClient 스트리밍 인터페이스 ───

    @Override
    public ChatCompletionResponse chatCompletionStream(List<ChatMessage> messages, SamplingParams params,
                                                         ChatCompletionRequest.ToolConfig toolConfig, String model,
                                                         Consumer<String> tokenCallback) {
        warnIfInsideTransaction();
        SamplingParams effective = capMaxTokensIfNeeded(messages, params, toolConfig);
        return doApiCallStream(messages, effective, toolConfig, tokenCallback);
    }

    // ─── vLLM 특화: thinking 활성화 + cap + retry override ───

    @Override
    protected ChatCompletionRequest buildRequest(List<ChatMessage> messages, SamplingParams params,
                                                   ChatCompletionRequest.ToolConfig toolConfig) {
        ChatCompletionRequest request = super.buildRequest(messages, params, toolConfig);
        Boolean enableThinking = providerConfig.getEnableThinking();
        if (enableThinking == null || enableThinking) {
            request.setChatTemplateKwargs(Map.of("enable_thinking", true));
        }
        return request;
    }

    @Override
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params,
                                                  ChatCompletionRequest.ToolConfig toolConfig) {
        warnIfInsideTransaction();
        SamplingParams effective = capMaxTokensIfNeeded(messages, params, toolConfig);
        try {
            return doApiCall(messages, effective, toolConfig);
        } catch (OpenAiClientException e) {
            // vLLM 400 에러 시 max_tokens 조정 후 1회 재시도
            if (e.getCause() instanceof HttpClientErrorException hce
                    && hce.getStatusCode().value() == 400) {
                Integer available = parseAvailableTokens(hce.getResponseBodyAsString());
                if (available != null && available > 0) {
                    int capped = Math.max(available, MIN_MAX_TOKENS);
                    log.warn("[VllmClient] max_tokens {} exceeds available {}, retrying with {}",
                            effective.maxTokens(), available, capped);
                    return doApiCall(messages, effective.withMaxTokens(capped), toolConfig);
                }
            }
            throw e;
        }
    }

    // ─── vLLM 특화 내부 로직 ───

    /**
     * contextLength 설정 시 max_tokens를 사전 조정하여 오버플로우 방지.
     * 도구 정의 JSON 크기도 입력 토큰에 포함하여 추정.
     */
    private SamplingParams capMaxTokensIfNeeded(List<ChatMessage> messages, SamplingParams params,
                                                  ChatCompletionRequest.ToolConfig toolConfig) {
        Integer contextLength = providerConfig.getContextWindow();
        if (contextLength == null || params.maxTokens() == null) return params;

        int estimatedInput = estimateInputTokens(messages, toolConfig);
        int available = contextLength - estimatedInput;

        if (params.maxTokens() > available) {
            int capped = Math.max(available, MIN_MAX_TOKENS);
            log.warn("[VllmClient] Proactive max_tokens cap: {} -> {} (input ~{} tokens, ctx {})",
                    params.maxTokens(), capped, estimatedInput, contextLength);
            return params.withMaxTokens(capped);
        }
        return params;
    }

    /**
     * 메시지 + 도구 정의의 입력 토큰을 보수적으로 추정 (1토큰 ~ 2문자).
     */
    private int estimateInputTokens(List<ChatMessage> messages,
                                      ChatCompletionRequest.ToolConfig toolConfig) {
        int totalChars = 0;
        for (ChatMessage msg : messages) {
            totalChars += 10; // role, formatting overhead
            String content = msg.getContentAsString();
            if (content != null) totalChars += content.length();
            if (msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    totalChars += 30; // id, type overhead
                    if (tc.getFunction() != null) {
                        if (tc.getFunction().getName() != null) totalChars += tc.getFunction().getName().length();
                        if (tc.getFunction().getArguments() != null) totalChars += tc.getFunction().getArguments().length();
                    }
                }
            }
        }
        if (toolConfig != null && toolConfig.getTools() != null) {
            for (var tool : toolConfig.getTools()) {
                totalChars += 20; // type, structure overhead
                if (tool.getFunction() != null) {
                    if (tool.getFunction().getName() != null) totalChars += tool.getFunction().getName().length();
                    if (tool.getFunction().getDescription() != null) totalChars += tool.getFunction().getDescription().length();
                    if (tool.getFunction().getParameters() != null) {
                        totalChars += tool.getFunction().getParameters().toString().length();
                    }
                }
            }
        }
        int charsPerToken = providerConfig.getCharsPerToken() != null
                ? providerConfig.getCharsPerToken() : CHARS_PER_TOKEN_ESTIMATE;
        return totalChars / charsPerToken + TOKEN_OVERHEAD;
    }

    /**
     * vLLM 400 에러에서 사용 가능한 토큰 수 파싱
     */
    private Integer parseAvailableTokens(String errorBody) {
        if (errorBody == null) return null;
        Matcher matcher = MAX_TOKENS_ERROR_PATTERN.matcher(errorBody);
        if (matcher.find()) {
            int contextLen = Integer.parseInt(matcher.group(1));
            int inputTokens = Integer.parseInt(matcher.group(2));
            return contextLen - inputTokens;
        }
        return null;
    }
}
