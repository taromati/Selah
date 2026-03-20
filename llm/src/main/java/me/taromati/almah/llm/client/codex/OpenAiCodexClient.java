package me.taromati.almah.llm.client.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.LlmAlertCallback;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmConstants;
import me.taromati.almah.llm.client.OpenAiClientException;
import me.taromati.almah.llm.client.ProviderCapabilities;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.util.LlmResponseSanitizer;
import me.taromati.almah.llm.config.LlmConfigProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class OpenAiCodexClient implements LlmClient {

    private static final String DEFAULT_MODEL = "gpt-5.4";
    private static final String DEFAULT_BASE_URL = "https://chatgpt.com/backend-api";

    private final CodexTokenManager tokenManager;
    private final LlmConfigProperties.ProviderConfig providerConfig;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private LlmAlertCallback alertCallback;

    public OpenAiCodexClient(CodexTokenManager tokenManager,
                              LlmConfigProperties.ProviderConfig providerConfig) {
        this.tokenManager = tokenManager;
        this.providerConfig = providerConfig;
        this.baseUrl = (providerConfig != null && providerConfig.getBaseUrl() != null
                && !providerConfig.getBaseUrl().isBlank())
                ? providerConfig.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(
                        providerConfig != null ? providerConfig.getConnectTimeoutSeconds() : 10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public void setAlertCallback(LlmAlertCallback alertCallback) {
        this.alertCallback = alertCallback;
    }

    // ─── LlmClient 인터페이스 ───

    @Override
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params,
                                                  ChatCompletionRequest.ToolConfig toolConfig, String model) {
        warnIfInsideTransaction();

        String token = tokenManager.getAccessToken();
        ObjectNode requestJson = buildRequestJson(messages, params, toolConfig, model);

        try {
            String rawBody = executeRequest(token, requestJson);
            JsonNode responseJson = parseSseResponse(rawBody);
            ChatCompletionResponse response = convertToCompletionResponse(responseJson);
            consecutiveFailures.set(0);
            LlmResponseSanitizer.sanitize(response);
            return response;
        } catch (OpenAiClientException e) {
            throw e;
        } catch (Exception e) {
            // HTTP 상태 코드 기반 분기
            if (e.getMessage() != null && e.getMessage().contains("status=401")) {
                log.warn("[OpenAiCodexClient] 401 received, retrying with fresh token");
                return retryWithFreshToken(messages, params, toolConfig, model);
            }
            handleFailure("OpenAI Codex API 호출 오류: " + e.getMessage(), e);
            throw new OpenAiClientException("Failed to call OpenAI Codex API: " + e.getMessage(), e);
        }
    }

    @Override
    public String getProviderName() {
        return "openai-codex";
    }

    @Override
    public String getDefaultModel() {
        return providerConfig != null && providerConfig.getModel() != null
                ? providerConfig.getModel() : DEFAULT_MODEL;
    }

    @Override
    public String getSystemPromptHints() {
        return """
                - 시간/조건이 명시된 작업은 조건을 먼저 확인하고 충족될 때만 실행하세요.
                - 조건이 충족된 작업은 바로 실행하세요. 사용자에게 "~할까요?" 확인을 구하지 마세요.
                - 한 응답에서 질문과 답변을 동시에 하지 마세요 (예: "~할까요? 네" 금지).""";
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

    // ─── 요청 변환 ───

    ObjectNode buildRequestJson(List<ChatMessage> messages, SamplingParams params,
                                ChatCompletionRequest.ToolConfig toolConfig, String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model != null ? model : getDefaultModel());
        root.put("stream", true);
        root.put("store", false);

        // system 메시지 → instructions
        StringBuilder instructions = new StringBuilder();
        ArrayNode input = objectMapper.createArrayNode();

        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            switch (role) {
                case "system" -> {
                    if (!instructions.isEmpty()) instructions.append("\n\n");
                    String sysText = msg.getContentAsString();
                    if (sysText != null) instructions.append(sysText);
                }
                case "user" -> {
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("role", "user");
                    String userText = msg.getContentAsString();
                    item.put("content", userText != null ? userText : "");
                    input.add(item);
                }
                case "assistant" -> {
                    if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                        // assistant + tool_calls → function_call 항목들
                        for (ChatCompletionResponse.ToolCall tc : msg.getToolCalls()) {
                            ObjectNode fcItem = objectMapper.createObjectNode();
                            fcItem.put("type", "function_call");
                            fcItem.put("call_id", tc.getId());
                            fcItem.put("name", tc.getFunction().getName());
                            fcItem.put("arguments", tc.getFunction().getArguments());
                            input.add(fcItem);
                        }
                    } else {
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("role", "assistant");
                        String asstText = msg.getContentAsString();
                        item.put("content", asstText != null ? asstText : "");
                        input.add(item);
                    }
                }
                case "tool" -> {
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("type", "function_call_output");
                    item.put("call_id", msg.getToolCallId());
                    String toolText = msg.getContentAsString();
                    item.put("output", toolText != null ? toolText : "");
                    input.add(item);
                }
                default -> log.warn("[OpenAiCodexClient] Unknown message role: {}", role);
            }
        }

        if (!instructions.isEmpty()) {
            root.put("instructions", instructions.toString());
        }
        root.set("input", input);

        // tools
        if (toolConfig != null && toolConfig.getTools() != null && !toolConfig.getTools().isEmpty()) {
            root.set("tools", convertTools(toolConfig));
            if (toolConfig.getToolChoice() != null) {
                root.put("tool_choice", toolConfig.getToolChoice());
            }
        }

        // reasoning 설정
        ObjectNode reasoning = objectMapper.createObjectNode();
        String effort = providerConfig != null && providerConfig.getReasoningEffort() != null
                ? providerConfig.getReasoningEffort() : "medium";
        reasoning.put("effort", effort);
        reasoning.put("summary", "auto");
        root.set("reasoning", reasoning);

        return root;
    }

    private ArrayNode convertTools(ChatCompletionRequest.ToolConfig toolConfig) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (ChatCompletionRequest.ToolDefinition td : toolConfig.getTools()) {
            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            tool.put("name", td.getFunction().getName());
            if (td.getFunction().getDescription() != null) {
                tool.put("description", td.getFunction().getDescription());
            }
            if (td.getFunction().getParameters() != null) {
                tool.set("parameters", objectMapper.valueToTree(td.getFunction().getParameters()));
            }
            tools.add(tool);
        }
        return tools;
    }

    // ─── HTTP + SSE ───

    private String executeRequest(String token, ObjectNode requestJson) throws Exception {
        String url = baseUrl + "/codex/responses";
        String body = objectMapper.writeValueAsString(requestJson);

        String accountId = tokenManager.getAccountId();

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(providerConfig != null ? providerConfig.getTimeoutSeconds() : 120))
                .header("Content-Type", "application/json")
                .header("accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .header("OpenAI-Beta", "responses=experimental")
                .header("originator", "codex_cli_rs")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (accountId != null && !accountId.isBlank()) {
            reqBuilder.header("chatgpt-account-id", accountId);
        }

        log.debug("[OpenAiCodexClient] Request URL: {}, model: {}", url, requestJson.path("model").asText());

        HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status != 200) {
            String errorBody = response.body();
            log.error("[OpenAiCodexClient] HTTP {} — {}", status, errorBody);

            String errorMsg = switch (status) {
                case 401 -> throw new RuntimeException("status=401: 인증 실패");
                case 403 -> "ChatGPT 구독이 필요하거나 만료되었습니다.";
                case 404, 422 -> "모델을 사용할 수 없습니다 (" + status + "): " + errorBody;
                case 429 -> "요청 한도 초과. 잠시 후 다시 시도하세요.";
                default -> status >= 500
                        ? "OpenAI Codex 서버 오류 (" + status + ")"
                        : "OpenAI Codex API 오류 (" + status + "): " + errorBody;
            };
            handleFailure(errorMsg, null);
            throw new OpenAiClientException(errorMsg);
        }

        return response.body();
    }

    JsonNode parseSseResponse(String rawBody) {
        // SSE 포맷: "event: xxx\ndata: {...}\n\n"
        // response.completed 이벤트의 data에서 response 객체 추출
        String currentEvent = null;
        JsonNode completedResponse = null;

        for (String line : rawBody.split("\n")) {
            line = line.strip();
            if (line.startsWith("event: ")) {
                currentEvent = line.substring(7).strip();
            } else if (line.startsWith("data: ") && "response.completed".equals(currentEvent)) {
                String jsonStr = line.substring(6);
                try {
                    JsonNode data = objectMapper.readTree(jsonStr);
                    // data 자체가 response 객체이거나 data.response
                    if (data.has("response")) {
                        completedResponse = data.get("response");
                    } else if (data.has("output")) {
                        completedResponse = data;
                    }
                } catch (Exception e) {
                    log.warn("[OpenAiCodexClient] SSE data 파싱 실패: {}", e.getMessage());
                }
            }
        }

        if (completedResponse == null) {
            // fallback: 마지막 response.done 또는 전체 데이터에서 추출 시도
            log.error("[OpenAiCodexClient] response.completed 이벤트를 찾지 못했습니다. rawBody 일부: {}",
                    rawBody.length() > 500 ? rawBody.substring(0, 500) + "..." : rawBody);
            throw new OpenAiClientException("Codex SSE 응답에서 response.completed 이벤트를 찾을 수 없습니다");
        }

        return completedResponse;
    }

    // ─── 응답 변환 ───

    ChatCompletionResponse convertToCompletionResponse(JsonNode responseJson) {
        ChatCompletionResponse result = new ChatCompletionResponse();
        result.setId(responseJson.path("id").asText(null));
        result.setModel(responseJson.path("model").asText(null));

        // output 배열 파싱
        JsonNode outputArray = responseJson.path("output");
        String textContent = null;
        List<ChatCompletionResponse.ToolCall> toolCalls = new ArrayList<>();

        if (outputArray.isArray()) {
            for (JsonNode outputItem : outputArray) {
                String type = outputItem.path("type").asText("");
                switch (type) {
                    case "message" -> {
                        JsonNode contentArr = outputItem.path("content");
                        if (contentArr.isArray() && !contentArr.isEmpty()) {
                            textContent = contentArr.get(0).path("text").asText(null);
                        }
                    }
                    case "function_call" -> {
                        ChatCompletionResponse.ToolCall tc = new ChatCompletionResponse.ToolCall();
                        tc.setId(outputItem.path("call_id").asText(null));
                        tc.setType("function");

                        ChatCompletionResponse.ToolCall.FunctionCall fc =
                                new ChatCompletionResponse.ToolCall.FunctionCall();
                        fc.setName(outputItem.path("name").asText(null));
                        fc.setArguments(outputItem.path("arguments").asText(null));
                        tc.setFunction(fc);

                        toolCalls.add(tc);
                    }
                    case "reasoning" -> { /* 무시 */ }
                    default -> log.debug("[OpenAiCodexClient] Unknown output type: {}", type);
                }
            }
        }

        // Choice 구성
        ChatCompletionResponse.ResponseMessage message = new ChatCompletionResponse.ResponseMessage();
        message.setRole("assistant");
        message.setContent(textContent);
        if (!toolCalls.isEmpty()) {
            message.setToolCalls(toolCalls);
        }

        String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";

        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(finishReason);

        result.setChoices(List.of(choice));

        // Usage
        JsonNode usageNode = responseJson.path("usage");
        if (!usageNode.isMissingNode()) {
            ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage();
            usage.setPromptTokens(usageNode.path("input_tokens").asInt(0));
            usage.setCompletionTokens(usageNode.path("output_tokens").asInt(0));
            usage.setTotalTokens(usageNode.path("total_tokens").asInt(0));
            result.setUsage(usage);
        }

        return result;
    }

    // ─── 유틸 ───

    private ChatCompletionResponse retryWithFreshToken(List<ChatMessage> messages, SamplingParams params,
                                                        ChatCompletionRequest.ToolConfig toolConfig, String model) {
        tokenManager.invalidateCache();
        String token = tokenManager.getAccessToken();
        ObjectNode requestJson = buildRequestJson(messages, params, toolConfig, model);

        try {
            String rawBody = executeRequest(token, requestJson);
            JsonNode responseJson = parseSseResponse(rawBody);
            ChatCompletionResponse response = convertToCompletionResponse(responseJson);
            consecutiveFailures.set(0);
            LlmResponseSanitizer.sanitize(response);
            return response;
        } catch (Exception e) {
            handleFailure("OpenAI Codex 토큰 갱신 후 재시도 실패: " + e.getMessage(), e);
            throw new OpenAiClientException("OpenAI Codex 토큰 갱신 후에도 인증 실패", e);
        }
    }

    private void handleFailure(String errorMessage, Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        log.error("[OpenAiCodexClient] Failure #{}: {}", failures, errorMessage);

        if (failures == LlmConstants.CONSECUTIVE_FAILURE_ALERT_THRESHOLD && alertCallback != null) {
            alertCallback.sendCriticalAlert("CODEX_CONSECUTIVE_FAILURES",
                    "OpenAI Codex API 연속 실패",
                    "연속 실패 횟수: " + failures + "\n마지막 에러: " + errorMessage);
        }
    }

    private void warnIfInsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            String callerInfo = getCallerInfo();
            log.error("[OpenAiCodexClient] TX 내부 LLM 호출 감지! Caller: {}", callerInfo);
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
                    && !className.contains("CodexClient")
                    && !className.contains("$$")) {
                return className + "." + element.getMethodName() + ":" + element.getLineNumber();
            }
        }
        return "unknown";
    }
}
