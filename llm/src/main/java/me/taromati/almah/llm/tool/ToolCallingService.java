package me.taromati.almah.llm.tool;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.config.LlmConfigProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.taromati.almah.llm.client.ProviderCapabilities;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tool Calling 통합 서비스
 * LLM 호출 시 tool_calls 처리 및 후속 호출 관리
 */
@Slf4j
@Service
public class ToolCallingService {

    private final LlmClient defaultClient;
    private final ToolRegistry toolRegistry;
    private final LlmConfigProperties config;

    public ToolCallingService(LlmClientResolver clientResolver, ToolRegistry toolRegistry, LlmConfigProperties config) {
        var available = clientResolver.getAvailableProviders();
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "LLM 프로바이더가 등록되지 않았습니다. config.yml의 llm.providers 설정을 확인하세요.");
        }
        this.defaultClient = clientResolver.resolve(available.getFirst());
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    /** 기본 클라이언트의 능력치 (외부에서 ToolCallingConfig 생성용) */
    public ProviderCapabilities getDefaultCapabilities() {
        return defaultClient.getCapabilities();
    }

    /**
     * 지정된 도구들의 정의 텍스트 총 문자 수 추정 (budget 계산용 위임)
     */
    public int estimateToolDefinitionChars(List<String> toolNames) {
        return toolRegistry.estimateDefinitionChars(toolNames);
    }

    /**
     * Tool Calling 루프 종료 사유
     */
    public enum TerminationReason {
        /** 정상 완료 (텍스트 응답 수신) */
        COMPLETED,
        /** 외부 취소 요청 (cancelCheck) */
        CANCELLED,
        /** 시간 한도 초과 */
        TIMEOUT,
        /** 컨텍스트 윈도우 예산 소진 */
        BUDGET_EXHAUSTED,
        /** 절대 라운드 상한 도달 */
        ROUND_CAP
    }

    /**
     * Tool Calling 결과
     */
    public record ToolCallingResult(
            String textResponse,
            List<byte[]> images,
            int totalTokens,
            List<ChatMessage> intermediateMessages,
            String model,
            /** 컨텍스트 예산/타임아웃/라운드 상한 초과로 강제 종료된 경우 true. 호출자가 [INCOMPLETE] 마커를 후처리할 수 있음. */
            boolean roundsExhausted,
            /** 루프 종료 사유 */
            TerminationReason terminationReason
    ) {
        /** 하위 호환: intermediateMessages 불필요한 호출자용 */
        public ToolCallingResult(String textResponse, List<byte[]> images, int totalTokens) {
            this(textResponse, images, totalTokens, List.of(), null, false, TerminationReason.COMPLETED);
        }

        public boolean hasImages() {
            return images != null && !images.isEmpty();
        }
    }

    /**
     * Tool Calling 루프 설정
     */
    public record ToolCallingConfig(
            int contextWindow,
            int charsPerToken,
            int maxDurationMinutes
    ) {
        /**
         * 프로바이더 능력치에서 ToolCallingConfig를 생성.
         * null이거나 미설정 필드는 안전한 기본값으로 폴백.
         */
        public static ToolCallingConfig fromCapabilities(ProviderCapabilities caps, int maxDurationMinutes) {
            int ctxWin = caps != null && caps.contextWindow() != null ? caps.contextWindow() : 32768;
            int cpt = caps != null && caps.charsPerToken() != null ? caps.charsPerToken() : 3;
            return new ToolCallingConfig(ctxWin, cpt, maxDurationMinutes);
        }
    }

    // ─── 기존 오버로드 (OpenAiClient 직접 사용, 변경 없음) ───

    /**
     * Tool Calling을 포함한 채팅 완성
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames) {
        return chatWithTools(messages, params, availableToolNames, (ToolExecutionFilter) null);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (toolChoice 지정)
     * ImageIntentDetector 등에서 "required"를 지정하여 도구 호출을 강제할 때 사용.
     *
     * @param toolChoice "auto", "required", "none" 또는 null (기본값 "auto")
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, String toolChoice) {
        return chatWithToolsCore(messages, params, availableToolNames, null, defaultClient, null,
                ToolCallingConfig.fromCapabilities(defaultClient.getCapabilities(), 5), () -> false, toolChoice, null, null, null);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (toolChoice + ToolCallingConfig 지정)
     * 타임아웃 등 커스텀 설정이 필요한 경우 사용.
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, String toolChoice,
                                           ToolCallingConfig toolConfig) {
        return chatWithToolsCore(messages, params, availableToolNames, null, defaultClient, null,
                toolConfig, () -> false, toolChoice, null, null, null);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (LlmClient + toolChoice + ToolCallingConfig 지정)
     * 호출자가 자체 LlmClient를 지정할 때 사용 (예: aichat의 qwen35-9b).
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, String toolChoice,
                                           ToolCallingConfig toolConfig, LlmClient client) {
        return chatWithToolsCore(messages, params, availableToolNames, null, client, null,
                toolConfig, () -> false, toolChoice, null, null, null);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (실행 전 필터 적용)
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, ToolExecutionFilter filter) {
        // 기존 호출: defaultClient를 LlmClient로 사용 (vLLM 고정)
        return chatWithTools(messages, params, availableToolNames, filter, defaultClient, null);
    }

    // ─── 멀티 프로바이더 오버로드 ───

    /**
     * Tool Calling을 포함한 채팅 완성 (프로바이더 지정, 기본 ToolCallingConfig 사용)
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, ToolExecutionFilter filter,
                                           LlmClient client, String model) {
        return chatWithTools(messages, params, availableToolNames, filter, client, model,
                ToolCallingConfig.fromCapabilities(client.getCapabilities(), 5));
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (컨텍스트 예산 기반 제한)
     *
     * maxToolRounds 하드 리밋 대신 컨텍스트 윈도우, 타임아웃, 절대 상한(100라운드)으로 제한합니다.
     * 간단한 도구는 수백 번, 큰 도구는 10여 번 → 자연스럽게 조절됩니다.
     *
     * @param toolConfig 컨텍스트 윈도우, 토큰 추정 비율, 타임아웃 설정
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, ToolExecutionFilter filter,
                                           LlmClient client, String model,
                                           ToolCallingConfig toolConfig) {
        return chatWithTools(messages, params, availableToolNames, filter, client, model, toolConfig, () -> false);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (외부 취소 지원)
     *
     * @param cancelCheck 매 라운드 시작 시 호출되어 true이면 루프 중단
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, ToolExecutionFilter filter,
                                           LlmClient client, String model,
                                           ToolCallingConfig toolConfig,
                                           Supplier<Boolean> cancelCheck) {
        return chatWithToolsCore(messages, params, availableToolNames, filter, client, model, toolConfig, cancelCheck, null, null, null, null);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (실시간 대화 루프 — 중간 텍스트 콜백 + 메시지 주입 지원)
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, ToolExecutionFilter filter,
                                           LlmClient client,
                                           Supplier<Boolean> cancelCheck,
                                           String model,
                                           String toolChoice,
                                           Consumer<String> onIntermediateText,
                                           Supplier<String> incomingMessagePoll) {
        Supplier<Boolean> effectiveCancelCheck = cancelCheck != null ? cancelCheck : () -> false;
        ToolCallingConfig config = ToolCallingConfig.fromCapabilities(client.getCapabilities(), 5);
        return chatWithToolsCore(messages, params, availableToolNames, filter, client, model, config,
                effectiveCancelCheck, toolChoice, onIntermediateText, incomingMessagePoll, null);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (SSE 스트리밍 + StreamingListener 지원)
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, ToolExecutionFilter filter,
                                           LlmClient client,
                                           Supplier<Boolean> cancelCheck,
                                           String model,
                                           String toolChoice,
                                           Consumer<String> onIntermediateText,
                                           Supplier<String> incomingMessagePoll,
                                           StreamingListener streamingListener) {
        Supplier<Boolean> effectiveCancelCheck = cancelCheck != null ? cancelCheck : () -> false;
        ToolCallingConfig config = ToolCallingConfig.fromCapabilities(client.getCapabilities(), 5);
        return chatWithToolsCore(messages, params, availableToolNames, filter, client, model, config,
                effectiveCancelCheck, toolChoice, onIntermediateText, incomingMessagePoll, streamingListener);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (LoopContext 기반 — 에이전트 전용)
     *
     * @param loopContext 루프 컨텍스트 (config, cancelCheck, toolChoice, callbacks)
     * @param streamingListener SSE 스트리밍 리스너 (null이면 동기 경로)
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, ToolExecutionFilter filter,
                                           LlmClient client, String model,
                                           LoopContext loopContext,
                                           StreamingListener streamingListener) {
        ToolCallingConfig effectiveConfig = loopContext != null ? loopContext.config()
                : ToolCallingConfig.fromCapabilities(client.getCapabilities(), 5);
        Supplier<Boolean> effectiveCancel = loopContext != null ? loopContext.cancelCheck() : () -> false;
        String effectiveChoice = loopContext != null ? loopContext.toolChoice() : null;
        Consumer<String> effectiveIntermediate = loopContext != null && loopContext.callbacks() != null
                ? loopContext.callbacks().onIntermediateText() : null;
        Supplier<String> effectivePoll = loopContext != null && loopContext.callbacks() != null
                ? loopContext.callbacks().incomingMessagePoll() : null;
        return chatWithToolsCore(messages, params, availableToolNames, filter, client, model,
                effectiveConfig, effectiveCancel, effectiveChoice,
                effectiveIntermediate, effectivePoll, streamingListener);
    }

    /**
     * Tool Calling 핵심 구현
     *
     * @param toolChoice          "auto", "required", "none" 또는 null (기본값 "auto")
     * @param onIntermediateText  content + tool_calls 동시 응답 시 content를 즉시 전달하는 콜백 (null 가능)
     *                            streamingListener non-null이면 호출 스킵 (중복 방지)
     * @param incomingMessagePoll 매 라운드 시작 시 폴링하여 주입할 user 메시지를 반환하는 Supplier (null 가능)
     * @param streamingListener   SSE 스트리밍 리스너 (null이면 동기 경로 유지)
     */
    private ToolCallingResult chatWithToolsCore(List<ChatMessage> messages, SamplingParams params,
                                                List<String> availableToolNames, ToolExecutionFilter filter,
                                                LlmClient client, String model,
                                                ToolCallingConfig toolConfig,
                                                Supplier<Boolean> cancelCheck,
                                                String toolChoice,
                                                Consumer<String> onIntermediateText,
                                                Supplier<String> incomingMessagePoll,
                                                StreamingListener streamingListener) {
        if (availableToolNames == null || availableToolNames.isEmpty()) {
            return chatWithoutTools(messages, params, client, model);
        }

        List<ChatCompletionRequest.ToolDefinition> tools = new ArrayList<>(toolRegistry.getTools(availableToolNames));
        if (tools.isEmpty()) {
            return chatWithoutTools(messages, params, client, model);
        }

        String effectiveToolChoice = toolChoice != null ? toolChoice : "auto";

        ChatCompletionRequest.ToolConfig requestToolConfig = ChatCompletionRequest.ToolConfig.builder()
                .tools(tools)
                .toolChoice(effectiveToolChoice)
                .build();

        // 동적 도구 로딩용: 현재 활성 도구 이름 추적
        Set<String> loadedToolNames = new LinkedHashSet<>();
        for (var t : tools) {
            if (t.getFunction() != null) loadedToolNames.add(t.getFunction().getName());
        }

        List<ChatMessage> currentMessages = new ArrayList<>(messages);
        List<byte[]> collectedImages = new ArrayList<>();
        List<ChatMessage> intermediate = new ArrayList<>();
        Set<String> executedToolCalls = new HashSet<>();
        int totalTokens = 0;
        String lastModel = null;
        int round = 0;
        Instant startTime = Instant.now();
        boolean budgetWarningIssued = false;
        int maxOutputTokens = params.maxTokens() != null ? params.maxTokens() : config.getToolCallingDefaultMaxOutputTokens();
        TerminationReason terminationReason = null;

        // S05: 도구 연속 실패 감지 (서킷 브레이커)
        int failureLimit = config.getToolCallingConsecutiveFailureLimit() > 0
                ? config.getToolCallingConsecutiveFailureLimit() : 3;
        ToolResultEvaluator resultEvaluator = new ToolResultEvaluator(failureLimit);

        // S07: 도구 결과 크기 제한
        int toolResultMaxChars = config.getToolResultMaxChars() > 0
                ? config.getToolResultMaxChars() : 10000;

        // S08: API 재시도 설정
        int apiRetryCount = config.getApiRetryCount() > 0
                ? config.getApiRetryCount() : 2;

        while (true) {
            // 라운드별 중복 호출 방지 리셋 (다른 라운드에서는 같은 도구 재호출 가능)
            executedToolCalls.clear();

            // 안전 체크 0: 외부 취소 요청 (S10: 매번 새로 평가, 캐시 안 함)
            if (cancelCheck.get()) {
                log.info("[ToolCallingService] Cancelled by external check at round {}", round);
                terminationReason = TerminationReason.CANCELLED;
                break;
            }

            // 사용자 메시지 수신
            if (incomingMessagePoll != null) {
                String incoming = incomingMessagePoll.get();
                if (incoming != null && !incoming.isBlank()) {
                    ChatMessage userMsg = ChatMessage.builder().role("user").content(incoming).build();
                    currentMessages.add(userMsg);
                    intermediate.add(userMsg);
                    log.info("[ToolCallingService] Injected user message at round {}: {}",
                            round, StringUtils.truncate(incoming, 100));
                }
            }

            // 안전 체크 1: 타임아웃
            if (Duration.between(startTime, Instant.now()).toMinutes() >= toolConfig.maxDurationMinutes()) {
                log.warn("[ToolCallingService] Tool calling timeout after {} minutes", toolConfig.maxDurationMinutes());
                terminationReason = TerminationReason.TIMEOUT;
                break;
            }

            // 안전 체크 2: 컨텍스트 예산 (메시지 + tools 정의 크기 합산)
            int estimatedTokens = estimateTokens(currentMessages, toolConfig.charsPerToken());
            int toolDefChars = 0;
            for (var t : tools) {
                if (t.getFunction() != null) {
                    toolDefChars += 50; // function definition overhead
                    if (t.getFunction().getName() != null) toolDefChars += t.getFunction().getName().length();
                    if (t.getFunction().getDescription() != null) toolDefChars += t.getFunction().getDescription().length();
                    if (t.getFunction().getParameters() != null) toolDefChars += t.getFunction().getParameters().toString().length();
                }
            }
            int toolTokens = toolDefChars / Math.max(toolConfig.charsPerToken(), 1);
            int remaining = toolConfig.contextWindow() - estimatedTokens - toolTokens - maxOutputTokens;
            if (remaining < config.getToolCallingMinTokenBuffer()) {
                log.warn("[ToolCallingService] Context budget exhausted: ~{} tokens used, {} remaining",
                        estimatedTokens, remaining);
                terminationReason = TerminationReason.BUDGET_EXHAUSTED;
                break;
            }

            // 안전 체크 3: 절대 상한
            if (round >= config.getToolCallingRoundCap()) {
                log.warn("[ToolCallingService] Hard round cap ({}) reached", config.getToolCallingRoundCap());
                terminationReason = TerminationReason.ROUND_CAP;
                break;
            }

            // S08: LLM API 호출 (재시도 지원)
            ChatCompletionResponse response;
            try {
                response = callLlmWithRetry(client, currentMessages, params, requestToolConfig, model,
                        streamingListener, apiRetryCount, cancelCheck);
            } catch (Exception e) {
                log.error("[ToolCallingService] LLM API permanent error: {}", e.getMessage());
                return new ToolCallingResult("LLM API 오류: " + e.getMessage(),
                        collectedImages, totalTokens, intermediate, lastModel, true, TerminationReason.TIMEOUT);
            }
            totalTokens += response.getUsage() != null ? response.getUsage().getTotalTokens() : 0;
            lastModel = response.getModel();

            // S04/S14: 스트리밍 라운드 종료 시 명시적 flush (빈 문자열 해킹 제거)
            if (streamingListener != null) {
                try { streamingListener.onFlush(); }
                catch (Exception e) { log.warn("[ToolCallingService] Flush failed: {}", e.getMessage()); }
            }

            // S15: content + tool_calls 동시 존재 시 처리
            if (response.hasToolCalls()) {
                String intermediateContent = response.getContent();
                if (intermediateContent != null && !intermediateContent.isBlank()) {
                    // S15: 스트리밍 모드에서는 이미 onToken으로 전달됨 → onIntermediateText 미호출
                    if (onIntermediateText != null && streamingListener == null) {
                        try { onIntermediateText.accept(intermediateContent); }
                        catch (Exception e) { log.warn("[ToolCallingService] Intermediate text callback failed: {}", e.getMessage()); }
                    }
                }
            }

            if (!response.hasToolCalls()) {
                String textResponse = response.getContent();
                if (round > 0) {
                    try {
                        String rewritten = client.rewriteResponse(currentMessages, textResponse, params);
                        if (rewritten != null) textResponse = rewritten;
                    } catch (Exception e) {
                        log.warn("[ToolCallingService] rewriteResponse failed, using original: {}", e.getMessage());
                    }
                }
                log.debug("[ToolCallingService] Final response (round {}): {}", round, StringUtils.truncate(textResponse, 100));
                return new ToolCallingResult(textResponse, collectedImages, totalTokens, intermediate, lastModel, false, TerminationReason.COMPLETED);
            }

            // S15: content + tool_calls를 하나의 assistant 메시지로 (이중 메시지 방지)
            String contentForAssistant = response.getContent();
            ChatMessage assistantToolMsg;
            if (contentForAssistant != null && !contentForAssistant.isBlank()) {
                assistantToolMsg = ChatMessage.assistantWithContentAndToolCalls(contentForAssistant, response.getToolCalls());
            } else {
                assistantToolMsg = ChatMessage.assistantWithToolCalls(response.getToolCalls());
            }
            intermediate.add(assistantToolMsg);
            currentMessages.add(assistantToolMsg);

            for (ChatCompletionResponse.ToolCall toolCall : response.getToolCalls()) {
                // S10: 매 도구 실행 전 cancelCheck 재평가 (캐시 아님)
                if (cancelCheck.get()) {
                    log.info("[ToolCallingService] Cancelled before executing tool: {}",
                            toolCall.getFunction() != null ? toolCall.getFunction().getName() : "unknown");
                    terminationReason = TerminationReason.CANCELLED;
                    break;
                }

                String toolName = toolCall.getFunction() != null ? toolCall.getFunction().getName() : "unknown";
                String arguments = toolCall.getFunction() != null ? toolCall.getFunction().getArguments() : null;

                log.info("[ToolCallingService] Round {}: Executing tool: {} with args: {}",
                        round, toolName, StringUtils.truncate(arguments, 100));

                // S05: 비활성화된 도구 체크
                if (resultEvaluator.isDisabled(toolName)) {
                    String disabledMsg = "도구 '" + toolName + "'이(가) 반복 실패하여 비활성화되었습니다. 다른 접근을 시도하세요.";
                    ChatMessage toolMsg = ChatMessage.toolResponse(toolCall.getId(), disabledMsg);
                    intermediate.add(toolMsg);
                    currentMessages.add(toolMsg);
                    continue;
                }

                // S14: 도구 상태 표시 — 시작
                String argsPreview = arguments != null ? StringUtils.truncate(arguments.replaceAll("\\s+", " "), 40) : "";
                if (streamingListener != null) {
                    try { streamingListener.onToolStart(toolName, argsPreview); }
                    catch (Exception e) { log.warn("[ToolCallingService] onToolStart failed: {}", e.getMessage()); }
                }

                // 중복 도구 호출 방지
                String toolCallKey = toolName + ":" + (arguments != null ? arguments : "");
                if (!executedToolCalls.add(toolCallKey)) {
                    log.warn("[ToolCallingService] Duplicate tool call skipped: {}", toolName);
                    ChatMessage dupMsg = ChatMessage.toolResponse(toolCall.getId(),
                            "이미 동일한 요청이 실행되었습니다. 다른 작업을 진행하세요.");
                    intermediate.add(dupMsg);
                    currentMessages.add(dupMsg);
                    continue;
                }

                // S06: 도구 실행 예외 격리 / S16: 타임아웃 적용
                ToolResult toolResult;
                final String toolNameFinal = toolName;
                final String argumentsFinal = arguments;
                int timeoutSeconds = config.getToolExecutionTimeoutSeconds();
                try {
                    var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        if (filter != null) {
                            String blocked = filter.checkPermission(toolNameFinal, argumentsFinal);
                            if (blocked != null) {
                                log.info("[ToolCallingService] Tool blocked by filter: {} - {}", toolNameFinal, blocked);
                                return ToolResult.failure("⛔ 도구 사용이 거부되었습니다: " + blocked);
                            }
                            return toolRegistry.execute(toolNameFinal, argumentsFinal);
                        }
                        return toolRegistry.execute(toolNameFinal, argumentsFinal);
                    });
                    toolResult = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    log.warn("[ToolCallingService] Tool execution timeout: {} ({}s)", toolName, timeoutSeconds);
                    toolResult = ToolResult.failure("도구 실행 타임아웃: " + toolName + " (" + timeoutSeconds + "초 초과)");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("[ToolCallingService] Tool execution error: {} — {}", cause.getClass().getSimpleName(), cause.getMessage());
                    toolResult = ToolResult.failure("도구 실행 오류: " + cause.getClass().getSimpleName() + " — " + cause.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[ToolCallingService] Tool execution interrupted: {}", toolName);
                    toolResult = ToolResult.failure("도구 실행 중단: " + toolName);
                }

                if (toolResult.hasImage()) {
                    collectedImages.add(toolResult.getImage());
                }

                // 동적 도구 로딩
                if (toolResult.getLoadTools() != null) {
                    for (String newTool : toolResult.getLoadTools()) {
                        if (loadedToolNames.add(newTool)) {
                            var def = toolRegistry.getDefinition(newTool);
                            if (def != null) tools.add(def);
                        }
                    }
                }

                String toolText = toolResult.getText();

                // S07: 도구 결과 크기 제한
                if (toolText != null && toolText.length() > toolResultMaxChars) {
                    int originalLength = toolText.length();
                    toolText = toolText.substring(0, toolResultMaxChars) +
                            "... (결과가 " + originalLength + "자로 절삭되었습니다)";
                }
                if (toolText == null) toolText = "(출력 없음)";

                if (toolResult.getFollowUpHint() != null) {
                    toolText = toolText + "\n\n[지시] " + toolResult.getFollowUpHint();
                }

                ChatMessage toolMsg = ChatMessage.toolResponse(
                        toolCall.getId() != null ? toolCall.getId() : "unknown",
                        toolText);
                intermediate.add(toolMsg);
                currentMessages.add(toolMsg);

                // S05: 도구 결과 평가 (연속 실패 감지)
                resultEvaluator.evaluate(toolName, toolResult);

                // S14: 도구 상태 표시 — 완료
                if (streamingListener != null) {
                    try {
                        String resultSummary = StringUtils.truncate(toolText.split("\n")[0], 50);
                        if (toolText.length() > 50) resultSummary += " (" + toolText.length() + "자)";
                        streamingListener.onToolDone(toolName, argsPreview, resultSummary);
                    } catch (Exception e) { log.warn("[ToolCallingService] onToolDone failed: {}", e.getMessage()); }
                }
            }

            // S10: 도구 루프 내 cancel 감지 시 종료
            if (terminationReason == TerminationReason.CANCELLED) break;

            // toolChoice=required → auto 전환
            boolean toolChoiceChanged = false;
            if ("required".equals(effectiveToolChoice)) {
                effectiveToolChoice = "auto";
                toolChoiceChanged = true;
                log.debug("[ToolCallingService] toolChoice switched: required → auto (after round {})", round);
            }

            // S05: 비활성화 도구를 tools 배열에서 제거
            Set<String> disabled = resultEvaluator.getDisabledTools();
            if (!disabled.isEmpty()) {
                tools.removeIf(t -> t.getFunction() != null && disabled.contains(t.getFunction().getName()));
                toolChoiceChanged = true; // requestToolConfig 재구성 트리거
            }

            // 동적 도구 로딩 또는 toolChoice 변경 시 requestToolConfig 재구성
            if (toolChoiceChanged || tools.size() != requestToolConfig.getTools().size()) {
                requestToolConfig = ChatCompletionRequest.ToolConfig.builder()
                        .tools(tools)
                        .toolChoice(effectiveToolChoice)
                        .build();
            }

            // Budget Notification
            if (!budgetWarningIssued) {
                int updatedEstimate = estimateTokens(currentMessages, toolConfig.charsPerToken());
                int updatedRemaining = toolConfig.contextWindow() - updatedEstimate - toolTokens - maxOutputTokens;
                if (updatedRemaining < toolConfig.contextWindow() * config.getToolCallingBudgetWarningRatio()) {
                    currentMessages.add(ChatMessage.builder()
                            .role("system")
                            .content("⚠️ 컨텍스트 예산이 부족합니다. 핵심 작업을 우선 수행하세요.")
                            .build());
                    budgetWarningIssued = true;
                    log.info("[ToolCallingService] Budget notification injected: ~{} tokens remaining", updatedRemaining);
                }
            }

            round++;
        }

        // 루프 종료 → 강제 텍스트 응답
        if (terminationReason == null) terminationReason = TerminationReason.TIMEOUT;
        log.warn("[ToolCallingService] Loop ended after {} rounds (reason={}), forcing text response", round, terminationReason);

        if (terminationReason == TerminationReason.CANCELLED) {
            return new ToolCallingResult(null, collectedImages, totalTokens, intermediate, lastModel, false, terminationReason);
        }

        // S13: 강제 요약 (스트리밍 모드로)
        currentMessages.add(ChatMessage.builder()
                .role("user")
                .content("[시스템] 컨텍스트 예산 또는 시간 한도에 도달했습니다. " +
                        "지금까지 수집한 정보를 기반으로 답변해주세요. " +
                        "미완료 작업이 있으면 답변 끝에 '[INCOMPLETE: 미완료 작업 설명]' 형식으로 명시해주세요.")
                .build());
        try {
            ChatCompletionResponse finalResponse;
            if (streamingListener != null) {
                finalResponse = client.chatCompletionStream(currentMessages, params, null, model, streamingListener::onToken);
                streamingListener.onFlush();
            } else {
                finalResponse = client.chatCompletion(currentMessages, params, null, model);
            }
            totalTokens += finalResponse.getUsage() != null ? finalResponse.getUsage().getTotalTokens() : 0;
            lastModel = finalResponse.getModel();

            String textResponse = finalResponse.getContent();
            try {
                String rewritten = client.rewriteResponse(currentMessages, textResponse, params);
                if (rewritten != null) textResponse = rewritten;
            } catch (Exception e) {
                log.warn("[ToolCallingService] rewriteResponse failed: {}", e.getMessage());
            }
            log.debug("[ToolCallingService] Final response (forced): {}", StringUtils.truncate(textResponse, 100));
            return new ToolCallingResult(textResponse, collectedImages, totalTokens, intermediate, lastModel, true, terminationReason);
        } catch (Exception e) {
            log.error("[ToolCallingService] Forced summary generation failed: {}", e.getMessage());
            return new ToolCallingResult("작업이 시간 내 완료되지 않았습니다.",
                    collectedImages, totalTokens, intermediate, lastModel, true, terminationReason);
        }
    }

    /**
     * S08: LLM API 호출 + 재시도 (지수 백오프).
     * Retryable: 429, 5xx, 네트워크 오류. Permanent: 401, 403, 기타 4xx.
     */
    private ChatCompletionResponse callLlmWithRetry(
            LlmClient client, List<ChatMessage> messages, SamplingParams params,
            ChatCompletionRequest.ToolConfig toolConfig, String model,
            StreamingListener streamingListener, int maxRetries, Supplier<Boolean> cancelCheck) {

        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0 && cancelCheck.get()) {
                throw new RuntimeException("Cancelled during LLM API retry");
            }

            try {
                if (streamingListener != null) {
                    return client.chatCompletionStream(messages, params, toolConfig, model, streamingListener::onToken);
                } else {
                    return client.chatCompletion(messages, params, toolConfig, model);
                }
            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";

                // Permanent errors: 401, 403 → no retry
                if (msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized") || msg.contains("Forbidden")) {
                    throw new RuntimeException("LLM 프로바이더 인증 오류: " + msg, e);
                }

                // Retryable: 429, 5xx, network errors
                if (attempt < maxRetries) {
                    long backoffMs = (long) Math.pow(2, attempt) * 1000; // 1s, 2s, 4s...
                    log.warn("[ToolCallingService] LLM API error (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, maxRetries + 1, backoffMs, msg);
                    try { Thread.sleep(backoffMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
                }
            }
        }
        throw new RuntimeException("LLM API " + (maxRetries + 1) + "회 시도 모두 실패: " +
                (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    /**
     * Tool 없이 채팅 완성 (기존 — defaultClient)
     */
    private ToolCallingResult chatWithoutTools(List<ChatMessage> messages, SamplingParams params) {
        return chatWithoutTools(messages, params, defaultClient, null);
    }

    /**
     * Tool 없이 채팅 완성 (멀티 프로바이더)
     */
    private ToolCallingResult chatWithoutTools(List<ChatMessage> messages, SamplingParams params,
                                               LlmClient client, String model) {
        ChatCompletionResponse response = client.chatCompletion(messages, params, null, model);
        int tokens = response.getUsage() != null ? response.getUsage().getTotalTokens() : 0;
        return new ToolCallingResult(response.getContent(), List.of(), tokens, List.of(), response.getModel(), false, TerminationReason.COMPLETED);
    }

    /**
     * 메시지 리스트의 토큰 수를 문자 수 기반으로 추정
     */
    private int estimateTokens(List<ChatMessage> messages, int charsPerToken) {
        int totalChars = 0;
        for (ChatMessage msg : messages) {
            totalChars += 10; // message overhead
            String text = msg.getContentAsString();
            if (text != null) totalChars += text.length();
            if (msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    totalChars += 20; // per-tool-call overhead
                    if (tc.getFunction() != null) {
                        if (tc.getFunction().getName() != null) totalChars += tc.getFunction().getName().length();
                        if (tc.getFunction().getArguments() != null) totalChars += tc.getFunction().getArguments().length();
                    }
                }
            }
        }
        return (int) Math.ceil((double) totalChars / Math.max(charsPerToken, 1) * config.getToolCallingTokenEstimationMultiplier());
    }

}
