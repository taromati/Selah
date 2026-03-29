package me.taromati.almah.agent.listener;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.chat.AutoHandoffHandler;
import me.taromati.almah.agent.chat.ChatOrchestrator;
import me.taromati.almah.agent.chat.EmbedResponseParser;
import me.taromati.almah.agent.chat.ChatTerminationClassifier;
import me.taromati.almah.agent.chat.HandoffResumptionHandler;
import me.taromati.almah.agent.chat.ChatSessionManager;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentSessionEntity;
import me.taromati.almah.agent.service.AgentSessionService;
import me.taromati.almah.agent.tool.ToolCommandListener;
import me.taromati.almah.core.messenger.*;
import me.taromati.almah.core.util.ConfigFileWriter;
import me.taromati.almah.core.util.PluginMdc;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.OpenAiClientException;
import me.taromati.almah.llm.tool.LoopCallbacks;
import me.taromati.almah.llm.tool.ToolCallingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Agent 채널 리스너 — 라우팅 전용.
 * 커맨드 파싱 + 적절한 서비스로 위임만 담당합니다.
 *
 * <h2>명령어 체계</h2>
 * <pre>
 * !도움말                              전체 명령어 안내
 *
 * [긴급]
 * !@                                   긴급 정지 (작업 중단, 세션 유지)
 *
 * [세션]
 * !초기화                              대화 세션 초기화
 *
 * [AI 모델]
 * !모델                                현재 모델/프로바이더 확인
 * !모델 변경 &lt;provider/model&gt;         모델 변경
 * !모델 목록                           사용 가능한 프로바이더 목록
 * !모델 초기화                         기동 시 설정값으로 복원
 *
 * [에이전트]
 * !작업 &lt;설명&gt;                        에이전트에게 작업 지시 + 즉시 실행
 * !점검실행                            에이전트 정기 점검 수동 실행
 *
 * [도구]
 * !도구 목록                           등록된 도구 전체 목록
 * !도구 &lt;도구명&gt; &lt;인자&gt;              도구 직접 실행 (한글 별칭 지원)
 * </pre>
 *
 * <p>확장 명령어(codex, routine, tool 등)는
 * {@link PluginCommandHandler} 구현체로 분리되어 자동 등록됩니다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentListener implements PluginListener {

    // config.getMaxContinuations(), config.getContinuationTimeoutMinutes() → AgentConfigProperties로 이동

    private final AgentConfigProperties config;
    private final AgentSessionService agentSessionService;
    private final LlmClientResolver clientResolver;
    private final ChatOrchestrator chatOrchestrator;
    private final ChatSessionManager chatSessionManager;
    private final AutoHandoffHandler autoHandoffHandler;
    private final HandoffResumptionHandler handoffResumptionHandler;
    private final MessengerGatewayRegistry messengerRegistry;
    private final List<PluginCommandHandler> commandHandlers;

    final ExecutorService chatExecutor = Executors.newCachedThreadPool();

    public AgentListener(
            AgentConfigProperties config,
            AgentSessionService agentSessionService,
            LlmClientResolver clientResolver,
            ChatOrchestrator chatOrchestrator,
            ChatSessionManager chatSessionManager,
            AutoHandoffHandler autoHandoffHandler,
            HandoffResumptionHandler handoffResumptionHandler,
            MessengerGatewayRegistry messengerRegistry,
            List<PluginCommandHandler> commandHandlers
    ) {
        this.config = config;
        this.agentSessionService = agentSessionService;
        this.clientResolver = clientResolver;
        this.chatOrchestrator = chatOrchestrator;
        this.chatSessionManager = chatSessionManager;
        this.autoHandoffHandler = autoHandoffHandler;
        this.handoffResumptionHandler = handoffResumptionHandler;
        this.messengerRegistry = messengerRegistry;
        this.commandHandlers = commandHandlers;
    }

    @PreDestroy
    public void shutdown() {
        chatExecutor.shutdownNow();
    }

    @Override
    public String getPluginName() {
        return "agent";
    }

    @Override
    public void onMessage(IncomingMessage message) {
        String content = message.content().trim();
        if (content.isEmpty()) {
            return;
        }

        ChannelRef channel = message.channel();
        String channelId = channel.channelId();

        // 1. !도움말 / !help
        if (content.equals("!도움말") || content.equals("!help")) {
            chatExecutor.submit(() -> handleHelpCommand(channel));
            return;
        }

        // 2. !@ — 긴급 정지 (세션 유지, 현재 루프만 중단)
        if (content.equals("!@")) {
            chatSessionManager.requestCancel(channelId);
            chatSessionManager.clearPendingMessages(channelId);
            messengerRegistry.sendText(channel, "⏹️ 멈췄어요!");
            return;
        }

        // 3. !초기화 / !reset
        if (content.equals("!초기화") || content.equals("!reset")) {
            chatSessionManager.requestCancel(channelId);
            chatSessionManager.clearPendingMessages(channelId);
            chatExecutor.submit(() -> {
                chatSessionManager.resetSession(channelId);
                messengerRegistry.sendText(channel, "\uD83D\uDD04 세션이 초기화되었습니다.");
            });
            return;
        }

        // 3. !모델 / !model (인증 서브커맨드는 PluginCommandHandler로 위임)
        if (content.startsWith("!모델") || content.startsWith("!model")) {
            // 인증 관련은 PluginCommandHandler에게 위임 시도
            for (PluginCommandHandler handler : commandHandlers) {
                if (handler.canHandle(content)) {
                    chatExecutor.submit(() -> handler.handle(channel, content, channelId));
                    return;
                }
            }
            // 인증이 아닌 모델 명령어는 직접 처리
            chatExecutor.submit(() -> handleModelCommand(channel, content, channelId));
            return;
        }

        // 4. 확장 명령어 핸들러 (codex, work, routine, tool 등)
        for (PluginCommandHandler handler : commandHandlers) {
            if (handler.canHandle(content)) {
                chatExecutor.submit(() -> handler.handle(channel, content, channelId));
                return;
            }
        }

        // 5. "이어해" 등 재개 요청 → 인터럽트 경로
        if (handoffResumptionHandler.isResumeRequest(content)) {
            submitChatOrQueue(channel, content, channelId);
            return;
        }

        // 6. 일반 메시지 → 인터럽트 경로
        submitChatOrQueue(channel, content, channelId);
    }

    // ─── !model 커맨드 ───

    private void handleModelCommand(ChannelRef channel, String content, String channelId) {
        String arg;
        if (content.startsWith("!모델")) {
            arg = content.substring("!모델".length()).trim();
        } else {
            String[] parts = content.split("\\s+", 2);
            arg = parts.length > 1 ? parts[1].trim() : "";
        }

        if (arg.isEmpty()) {
            AgentSessionEntity session = agentSessionService.getOrCreateActiveSession(channelId);
            String provider = config.getLlmProviderName();
            String model = session.getLlmModel() != null ? session.getLlmModel() : "(기본값)";
            messengerRegistry.sendText(channel,
                    "**LLM 설정**\n프로바이더: `" + provider + "`\n모델: `" + model + "`\n" +
                    "사용 가능: " + clientResolver.getAvailableProviders());
            return;
        }

        // reset / 초기화
        if (arg.equals("reset") || arg.equals("초기화")) {
            String initialProvider = config.getInitialLlmProviderName();
            config.setLlmProviderName(initialProvider);
            try {
                ConfigFileWriter.updateOrAddYamlValue("plugins.agent.llm-provider-name", initialProvider);
            } catch (java.io.IOException e) {
                log.warn("[Agent] config.yml 업데이트 실패: {}", e.getMessage());
            }
            AgentSessionEntity session = agentSessionService.getOrCreateActiveSession(channelId);
            agentSessionService.updateLlmModel(session.getId(), null);
            messengerRegistry.sendText(channel, "\uD83D\uDD04 LLM 설정이 초기화되었습니다. (" + initialProvider + " / 기본 모델)");
            return;
        }

        // list / 목록
        if (arg.equals("list") || arg.equals("목록")) {
            messengerRegistry.sendText(channel,
                    "**사용 가능한 프로바이더**: " + clientResolver.getAvailableProviders() + "\n" +
                    "사용법: `!모델 변경 <provider>/<model>` (예: `!모델 변경 openai/gpt-4.1`)");
            return;
        }

        // "변경" 접두사 처리
        String providerModel = arg;
        if (arg.startsWith("변경 ") || arg.startsWith("변경\t")) {
            providerModel = arg.substring(3).trim();
        }

        // provider/model 파싱
        String provider;
        String model;
        int slashIdx = providerModel.indexOf('/');
        if (slashIdx >= 0) {
            provider = providerModel.substring(0, slashIdx);
            model = providerModel.substring(slashIdx + 1);
            if (model.isBlank()) model = null;
        } else {
            provider = providerModel;
            model = null;
        }

        // 프로바이더 유효성 검증
        try {
            clientResolver.resolve(provider);
        } catch (IllegalArgumentException e) {
            messengerRegistry.sendText(channel, "\u274C " + e.getMessage());
            return;
        }

        // 프로바이더 설정 (config + in-memory)
        config.setLlmProviderName(provider);
        try {
            ConfigFileWriter.updateOrAddYamlValue("plugins.agent.llm-provider-name", provider);
        } catch (java.io.IOException e) {
            log.warn("[Agent] config.yml 업데이트 실패: {}", e.getMessage());
        }

        // 모델 설정 (세션 DB)
        AgentSessionEntity session = agentSessionService.getOrCreateActiveSession(channelId);
        agentSessionService.updateLlmModel(session.getId(), model);

        String modelDisplay = model != null ? model : "(기본값)";
        messengerRegistry.sendText(channel,
                "\u2705 LLM 변경: `" + provider + "` / `" + modelDisplay + "`");

        log.info("[AgentListener] Model changed: provider={}, model={}", provider, model);
    }

    // ─── 인터럽트 경로 ───

    private static final String HANDOFF_MARKER_PREFIX = "[HANDOFF_RESUME:";

    private void submitChatOrQueue(ChannelRef channel, String message, String channelId) {
        if (!chatSessionManager.tryAcquireOrQueue(channelId, message)) {
            return;
        }
        chatExecutor.submit(() -> handleChatLoop(channel, message, channelId));
    }

    private void handleChatLoop(ChannelRef channel, String initialMessage, String channelId) {
        executeWithTypingIndicator(channel, () -> {
            try {
                processSingleTurn(channel, initialMessage, channelId);
                while (true) {
                    var pending = chatSessionManager.drainPendingOrRelease(channelId);
                    if (pending.isEmpty()) break;
                    chatSessionManager.clearCancel(channelId);
                    String combined = String.join("\n", pending);
                    processSingleTurn(channel, combined, channelId);
                }
            } catch (Exception e) {
                chatSessionManager.forceRelease(channelId);
                throw e;
            }
        });
    }

    ChatOrchestrator.ChatResult processSingleTurn(ChannelRef channel, String userMessage, String channelId) {
        if (isHandoffResumeMarker(userMessage)) {
            String taskIdPrefix = extractTaskIdPrefix(userMessage);
            var ctxOpt = handoffResumptionHandler.findByIdPrefix(taskIdPrefix);
            if (ctxOpt.isEmpty()) {
                log.warn("[AgentListener] HANDOFF 마커의 task를 찾을 수 없음: {}", taskIdPrefix);
                return null;
            }
            var ctx = ctxOpt.get();
            var result = processResumeTurn(channel, ctx, channelId);
            if (result != null && !result.isCancelled() && !result.roundsExhausted()) {
                handoffResumptionHandler.markCompleted(ctx.taskId());
            }
            return result;
        }

        if (handoffResumptionHandler.isResumeRequest(userMessage)) {
            return processResumeTurn(channel, channelId);
        }

        return processRegularChatTurn(channel, userMessage, channelId);
    }

    private ChatOrchestrator.ChatResult processResumeTurn(ChannelRef channel, String channelId) {
        var resumable = handoffResumptionHandler.findResumable();
        if (resumable.isEmpty()) {
            return processRegularChatTurn(channel, "이어해", channelId);
        }
        var ctx = resumable.get();
        var result = processResumeTurn(channel, ctx, channelId);
        if (result != null && !result.isCancelled() && !result.roundsExhausted()) {
            handoffResumptionHandler.markCompleted(ctx.taskId());
        }
        return result;
    }

    ChatOrchestrator.ChatResult processResumeTurn(ChannelRef channel,
                                                   HandoffResumptionHandler.ResumptionContext ctx,
                                                   String channelId) {
        messengerRegistry.sendText(channel,
                "\uD83D\uDCCB 이전 작업 '" + StringUtils.truncate(ctx.title(), 50) + "'을 이어서 진행합니다.");

        // 재개 턴도 일반 턴과 동일한 이어하기 루프를 사용 (S07)
        String initialPrompt = handoffResumptionHandler.buildResumptionPrompt(ctx);
        return processRegularChatTurn(channel, initialPrompt, channelId);
    }

    static boolean isHandoffResumeMarker(String message) {
        return message != null && message.startsWith(HANDOFF_MARKER_PREFIX) && message.endsWith("]");
    }

    private static String extractTaskIdPrefix(String marker) {
        return marker.substring(HANDOFF_MARKER_PREFIX.length(), marker.length() - 1);
    }

    private ChatOrchestrator.ChatResult processRegularChatTurn(ChannelRef channel, String userMessage, String channelId) {
        var session = chatSessionManager.getOrCreateSession(channelId);
        agentSessionService.generateTitle(session.getId(), userMessage);

        int continuations = 0;
        String currentMessage = userMessage;
        ChatTerminationClassifier.Classification lastClassification = null;
        String lastResponse = null;

        // StreamingMessageHandler: 플레이스홀더 전송 + 스트리밍 콜백 일체형 (S04)
        StreamingMessageHandler handler = new StreamingMessageHandler(messengerRegistry, channel,
                config.getStreamingDebounceMs(), config.getStreamingSplitThreshold());

        // 실시간 중간 텍스트 콜백 — 디스코드에 즉시 전송 (Phase 2 호환)
        Consumer<String> onIntermediateText = text -> {
            var parsed = EmbedResponseParser.parse(text);
            if (parsed.hasEmbeds()) {
                if (parsed.text() != null && !parsed.text().isBlank()) {
                    messengerRegistry.sendText(channel, parsed.text());
                }
                for (var embed : parsed.embeds()) {
                    messengerRegistry.sendWithEmbed(channel, null, embed);
                }
            } else {
                messengerRegistry.sendText(channel, text);
            }
        };

        LoopCallbacks loopCallbacks = new LoopCallbacks(
                onIntermediateText,
                () -> {
                    String msg = chatSessionManager.drainPending(channelId);
                    if (msg != null) {
                        chatSessionManager.saveMessage(session.getId(), "user", msg, null, null, null);
                    }
                    return msg;
                }
        );

        while (true) {
            ChatOrchestrator.ChatResult result;
            try {
                result = chatOrchestrator.handle(channelId, currentMessage, channel, loopCallbacks, handler);
            } catch (Exception e) {
                log.error("[AgentListener] chatOrchestrator.handle error: {}", e.getMessage(), e);
                handler.forceEnd("❌ 처리 중 오류가 발생했습니다: " + e.getMessage());
                throw e;
            }

            if (result.isCancelled()) {
                handler.cancel();
                return result;
            }

            // 정상/라운드 소진 모두: 잔여 버퍼 확정 + embed → 인용 변환 (부분 응답 먼저 표시)
            handler.finalizeMessage();

            String responseText = result.response();
            if (responseText == null || responseText.isBlank()) responseText = "(빈 응답)";
            lastResponse = responseText;
            lastClassification = result.classification();

            // 이미지 처리: handler가 메시지를 관리하므로 이미지만 별도 전송
            if (handler.getMessageId() != null) {
                if (result.images() != null && !result.images().isEmpty()) {
                    messengerRegistry.sendWithImages(channel, null, result.images());
                }
            } else if (result.images() != null && !result.images().isEmpty()) {
                messengerRegistry.sendWithImages(channel, responseText, result.images());
            } else {
                // handler messageId가 null (스트리밍 불가 경로) — fallback 전송
                var parsed = EmbedResponseParser.parse(responseText);
                if (parsed.hasEmbeds()) {
                    if (parsed.text() != null && !parsed.text().isBlank()) {
                        messengerRegistry.sendText(channel, parsed.text());
                    }
                    for (var embed : parsed.embeds()) {
                        messengerRegistry.sendWithEmbed(channel, null, embed);
                    }
                } else {
                    messengerRegistry.sendText(channel, responseText);
                }
            }

            if (!result.roundsExhausted()) return result;

            var exitReason = lastClassification.reason();

            if (exitReason == ChatTerminationClassifier.ChatExitReason.TOOL_FAILURE) {
                messengerRegistry.sendText(channel,
                        "\u26A0\uFE0F 도구 연결 문제로 처리하지 못했습니다: " + lastClassification.detail());
                return result;
            }

            if (continuations >= config.getMaxContinuations()) {
                String taskId = autoHandoffHandler.handleClassified(userMessage, lastResponse, lastClassification);
                if (taskId != null) {
                    InteractiveMessage msg = new InteractiveMessage(
                            "\u23F3 작업이 시간 내 완료되지 않아 할 일에 등록했습니다.",
                            List.of(new InteractiveMessage.Action(
                                    "agent-resume:" + taskId.substring(0, 8), "이어하기",
                                    InteractiveMessage.ActionStyle.PRIMARY)));
                    messengerRegistry.sendInteractive(channel, msg);
                } else {
                    messengerRegistry.sendText(channel,
                            "\u23F3 작업이 시간 내 완료되지 않아 할 일에 등록했습니다.");
                }
                return result;
            }

            String continuationNote = "";
            if (exitReason == ChatTerminationClassifier.ChatExitReason.PROGRESS_STALLED) {
                continuationNote = "\n\u26A0\uFE0F 진행이 정체되었습니다 \u2014 " + lastClassification.detail();
            }

            boolean approved = requestContinuation(channel, continuations + 1, config.getMaxContinuations(), continuationNote, channelId);
            if (!approved) {
                // S05: pending 메시지 유무와 무관하게 항상 핸드오프 실행
                // pending 메시지가 있으면 handleChatLoop의 drain 루프에서 새 턴 시작
                String taskId = autoHandoffHandler.handleClassified(userMessage, lastResponse, lastClassification);
                if (taskId != null) {
                    InteractiveMessage msg = new InteractiveMessage(
                            "\uD83D\uDCCB 작업을 저장했습니다.",
                            List.of(new InteractiveMessage.Action(
                                    "agent-resume:" + taskId.substring(0, 8), "이어하기",
                                    InteractiveMessage.ActionStyle.PRIMARY)));
                    messengerRegistry.sendInteractive(channel, msg);
                } else {
                    messengerRegistry.sendText(channel, "\uD83D\uDCCB 작업을 저장했습니다.");
                }
                return result;
            }

            // S11: 새 라운드 — 새 플레이스홀더 + 버퍼/히스토리 초기화
            handler.newRound();

            if (exitReason == ChatTerminationClassifier.ChatExitReason.PROGRESS_STALLED) {
                currentMessage = "[시스템 요청] 이전 작업을 이어서 진행해주세요. " +
                        "\u26A0\uFE0F 이전 시도에서 동일한 접근이 반복 실패했습니다. 다른 방법을 시도하세요.";
            } else {
                currentMessage = "[시스템 요청] 이전 작업을 이어서 진행해주세요.";
            }
            continuations++;
        }
    }

    // ─── 이어하기 승인 ───

    private boolean requestContinuation(ChannelRef channel, int current, int max, String note, String channelId) {
        String text = String.format(
                "\u26A0\uFE0F 도구 호출 한도에 도달했습니다. 이어서 진행할까요? (%d/%d)",
                current, max);
        if (note != null && !note.isEmpty()) {
            text += note;
        }

        InteractiveMessage message = new InteractiveMessage(text, List.of(
                new InteractiveMessage.Action("agent-continue-yes", "이어서 진행", InteractiveMessage.ActionStyle.SUCCESS),
                new InteractiveMessage.Action("agent-continue-no", "종료", InteractiveMessage.ActionStyle.DANGER)
        ));

        InteractiveMessageHandle handle = messengerRegistry.sendInteractive(channel, message);
        if (handle == null) return false;

        CompletableFuture<Boolean> boolFuture = new CompletableFuture<>();
        handle.getFuture().thenAccept(actionEvent -> {
            boolean yes = "agent-continue-yes".equals(actionEvent.actionId());
            boolFuture.complete(yes);
        });
        chatSessionManager.setContinuationFuture(channelId, boolFuture);
        try {
            // boolFuture로 대기 — 버튼 클릭(handle.getFuture→boolFuture)과
            // !@ 취소(completeContinuationFuture→boolFuture) 모두 즉시 해제
            Boolean approved = boolFuture.get(config.getContinuationTimeoutMinutes(), TimeUnit.MINUTES);
            boolean yes = Boolean.TRUE.equals(approved);
            handle.editText(text + (yes ? "\n\u2705 이어서 진행합니다." : "\n\u274C 종료되었습니다."));
            return yes;
        } catch (java.util.concurrent.TimeoutException e) {
            handle.editText(text + "\n\u23F0 시간 초과");
            return false;
        } catch (Exception e) {
            handle.editText(text + "\n\u23F0 시간 초과");
            return false;
        } finally {
            chatSessionManager.clearContinuationFuture(channelId);
        }
    }

    // ─── !도움말 ───

    private void handleHelpCommand(ChannelRef channel) {
        String help = """
                **Agent 명령어 안내**

                **[세션]**
                `!@` — 긴급 정지 (작업 중단, 세션 유지)
                `!초기화` — 대화 세션 초기화

                **[AI 모델]**
                `!모델` — 현재 모델/프로바이더 확인
                `!모델 변경 <provider/model>` — 모델 변경
                `!모델 목록` — 사용 가능한 프로바이더 목록
                `!모델 초기화` — 기동 시 설정값으로 복원

                **[에이전트]**
                `!작업 <설명>` — 작업 지시 + 즉시 실행
                `!점검실행` — 정기 점검 수동 실행

                **[도구]**
                `!도구 목록` — 전체 도구 목록 (설명 포함)
                `!도구 <도구명> <인자>` — 도구 직접 실행
                `!<도구명> <인자>` — 도구 직접 실행 (축약형)

                **한글 별칭:** %s

                `!도움말` — 이 안내 보기""".formatted(
                ToolCommandListener.getToolAliases().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "))
        );

        messengerRegistry.sendText(channel, help);
    }

    // ─── 유틸리티 ───

    void executeWithTypingIndicator(ChannelRef channel, Runnable action) {
        try (TypingHandle ignored = messengerRegistry.startTyping(channel)) {
            PluginMdc.set("agent");
            try {
                action.run();
            } catch (OpenAiClientException e) {
                log.error("[AgentListener] Chat error: {}", e.getMessage());
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
                messengerRegistry.sendText(channel, "\u274C AI 응답 생성 중 오류가 발생했습니다: " + errMsg);
            } catch (Exception e) {
                log.error("[AgentListener] Unexpected error: {}", e.getMessage(), e);
                messengerRegistry.sendText(channel, "\u274C 예기치 않은 오류가 발생했습니다.");
            } finally {
                PluginMdc.clear();
            }
        }
    }
}
