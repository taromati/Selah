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
                ToolCallingConfig.fromCapabilities(defaultClient.getCapabilities(), 5), () -> false, toolChoice);
    }

    /**
     * Tool Calling을 포함한 채팅 완성 (toolChoice + ToolCallingConfig 지정)
     * 타임아웃 등 커스텀 설정이 필요한 경우 사용.
     */
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, SamplingParams params,
                                           List<String> availableToolNames, String toolChoice,
                                           ToolCallingConfig toolConfig) {
        return chatWithToolsCore(messages, params, availableToolNames, null, defaultClient, null,
                toolConfig, () -> false, toolChoice);
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
        return chatWithToolsCore(messages, params, availableToolNames, filter, client, model, toolConfig, cancelCheck, null);
    }

    /**
     * Tool Calling 핵심 구현 (toolChoice 파라미터 포함)
     *
     * @param toolChoice "auto", "required", "none" 또는 null (기본값 "auto")
     */
    private ToolCallingResult chatWithToolsCore(List<ChatMessage> messages, SamplingParams params,
                                                List<String> availableToolNames, ToolExecutionFilter filter,
                                                LlmClient client, String model,
                                                ToolCallingConfig toolConfig,
                                                Supplier<Boolean> cancelCheck,
                                                String toolChoice) {
        if (availableToolNames == null || availableToolNames.isEmpty()) {
            return chatWithoutTools(messages, params, client, model);
        }

        List<ChatCompletionRequest.ToolDefinition> tools = toolRegistry.getTools(availableToolNames);
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

        while (true) {
            // 안전 체크 0: 외부 취소 요청
            if (cancelCheck.get()) {
                log.info("[ToolCallingService] Cancelled by external check at round {}", round);
                terminationReason = TerminationReason.CANCELLED;
                break;
            }

            // 안전 체크 1: 타임아웃
            if (Duration.between(startTime, Instant.now()).toMinutes() >= toolConfig.maxDurationMinutes()) {
                log.warn("[ToolCallingService] Tool calling timeout after {} minutes", toolConfig.maxDurationMinutes());
                terminationReason = TerminationReason.TIMEOUT;
                break;
            }

            // 안전 체크 2: 컨텍스트 예산 (가용 토큰이 임계치 미만이면 중단)
            int estimatedTokens = estimateTokens(currentMessages, toolConfig.charsPerToken());
            int remaining = toolConfig.contextWindow() - estimatedTokens - maxOutputTokens;
            if (remaining < config.getToolCallingMinTokenBuffer()) {
                log.warn("[ToolCallingService] Context budget exhausted: ~{} tokens used, {} remaining",
                        estimatedTokens, remaining);
                terminationReason = TerminationReason.BUDGET_EXHAUSTED;
                break;
            }

            // 안전 체크 3: 절대 상한 (극단적 안전장치)
            if (round >= config.getToolCallingRoundCap()) {
                log.warn("[ToolCallingService] Hard round cap ({}) reached", config.getToolCallingRoundCap());
                terminationReason = TerminationReason.ROUND_CAP;
                break;
            }

            ChatCompletionResponse response = client.chatCompletion(currentMessages, params, requestToolConfig, model);
            totalTokens += response.getUsage() != null ? response.getUsage().getTotalTokens() : 0;
            lastModel = response.getModel();

            if (!response.hasToolCalls()) {
                String textResponse = response.getContent();
                if (round > 0) {
                    String rewritten = client.rewriteResponse(currentMessages, textResponse, params);
                    if (rewritten != null) textResponse = rewritten;
                }

                log.debug("[ToolCallingService] Final response (round {}): {}", round, StringUtils.truncate(textResponse, 100));
                return new ToolCallingResult(textResponse, collectedImages, totalTokens, intermediate, lastModel, false, TerminationReason.COMPLETED);
            }

            // tool_calls 실행
            ChatMessage assistantToolMsg = ChatMessage.assistantWithToolCalls(response.getToolCalls());
            intermediate.add(assistantToolMsg);
            currentMessages.add(assistantToolMsg);

            for (ChatCompletionResponse.ToolCall toolCall : response.getToolCalls()) {
                // 도구 호출 전 취소 체크
                if (cancelCheck != null && cancelCheck.get()) {
                    log.info("[ToolCallingService] Cancelled before executing tool: {}", toolCall.getFunction().getName());
                    break;
                }

                String toolName = toolCall.getFunction().getName();
                String arguments = toolCall.getFunction().getArguments();

                log.info("[ToolCallingService] Round {}: Executing tool: {} with args: {}",
                        round, toolName, StringUtils.truncate(arguments, 100));

                // 중복 도구 호출 방지: 동일 도구 + 동일 인자는 재실행하지 않음
                String toolCallKey = toolName + ":" + (arguments != null ? arguments : "");
                if (!executedToolCalls.add(toolCallKey)) {
                    log.warn("[ToolCallingService] Duplicate tool call skipped: {}", toolName);
                    ChatMessage dupMsg = ChatMessage.toolResponse(toolCall.getId(),
                            "이미 동일한 요청이 실행되었습니다. 다른 작업을 진행하세요.");
                    intermediate.add(dupMsg);
                    currentMessages.add(dupMsg);
                    continue;
                }

                ToolResult toolResult;
                if (filter != null) {
                    String blocked = filter.checkPermission(toolName, arguments);
                    if (blocked != null) {
                        log.info("[ToolCallingService] Tool blocked by filter: {} - {}", toolName, blocked);
                        toolResult = ToolResult.text("⛔ 도구 사용이 거부되었습니다: " + blocked);
                    } else {
                        toolResult = toolRegistry.execute(toolName, arguments);
                    }
                } else {
                    toolResult = toolRegistry.execute(toolName, arguments);
                }

                if (toolResult.hasImage()) {
                    collectedImages.add(toolResult.getImage());
                }

                // 동적 도구 로딩: loadTools가 있으면 다음 라운드부터 해당 도구를 tools 배열에 추가
                if (toolResult.getLoadTools() != null) {
                    for (String newTool : toolResult.getLoadTools()) {
                        if (loadedToolNames.add(newTool)) {
                            var def = toolRegistry.getDefinition(newTool);
                            if (def != null) {
                                tools.add(def);
                            }
                        }
                    }
                }

                String toolText = toolResult.getText();
                if (toolResult.getFollowUpHint() != null) {
                    toolText = toolText + "\n\n[지시] " + toolResult.getFollowUpHint();
                }
                ChatMessage toolMsg = ChatMessage.toolResponse(toolCall.getId(), toolText);
                intermediate.add(toolMsg);
                currentMessages.add(toolMsg);
            }

            // toolChoice=required는 최초 1라운드만 적용, 이후 auto로 전환
            // (required가 유지되면 LLM이 텍스트 응답 없이 tool call만 반복하는 루프에 빠짐)
            boolean toolChoiceChanged = false;
            if ("required".equals(effectiveToolChoice)) {
                effectiveToolChoice = "auto";
                toolChoiceChanged = true;
                log.debug("[ToolCallingService] toolChoice switched: required → auto (after round {})", round);
            }

            // 동적 도구 로딩 또는 toolChoice 변경 시 requestToolConfig 재구성
            if (tools.size() != requestToolConfig.getTools().size() || toolChoiceChanged) {
                requestToolConfig = ChatCompletionRequest.ToolConfig.builder()
                        .tools(tools)
                        .toolChoice(effectiveToolChoice)
                        .build();
                if (tools.size() != requestToolConfig.getTools().size()) {
                    log.info("[ToolCallingService] Tools expanded: {} definitions", tools.size());
                }
            }

            // Budget Notification: 컨텍스트 예산의 설정 비율 이하일 때 경고
            if (!budgetWarningIssued) {
                int updatedEstimate = estimateTokens(currentMessages, toolConfig.charsPerToken());
                int updatedRemaining = toolConfig.contextWindow() - updatedEstimate - maxOutputTokens;
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

        // 루프 종료 → 강제 텍스트 응답 + 미완료 마커 안내
        if (terminationReason == null) terminationReason = TerminationReason.TIMEOUT;
        log.warn("[ToolCallingService] Loop ended after {} rounds (reason={}), forcing text response", round, terminationReason);

        // 취소 시에는 강제 응답 없이 즉시 반환
        if (terminationReason == TerminationReason.CANCELLED) {
            return new ToolCallingResult(null, collectedImages, totalTokens, intermediate, lastModel, false, terminationReason);
        }

        currentMessages.add(ChatMessage.builder()
                .role("user")
                .content("[시스템] 컨텍스트 예산 또는 시간 한도에 도달했습니다. " +
                        "지금까지 수집한 정보를 기반으로 답변해주세요. " +
                        "미완료 작업이 있으면 답변 끝에 '[INCOMPLETE: 미완료 작업 설명]' 형식으로 명시해주세요.")
                .build());
        ChatCompletionResponse finalResponse = client.chatCompletion(currentMessages, params, null, model);
        totalTokens += finalResponse.getUsage() != null ? finalResponse.getUsage().getTotalTokens() : 0;
        lastModel = finalResponse.getModel();

        String textResponse = finalResponse.getContent();
        String rewritten = client.rewriteResponse(currentMessages, textResponse, params);
        if (rewritten != null) textResponse = rewritten;
        log.debug("[ToolCallingService] Final response (forced): {}", StringUtils.truncate(textResponse, 100));
        return new ToolCallingResult(textResponse, collectedImages, totalTokens, intermediate, lastModel, true, terminationReason);
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
