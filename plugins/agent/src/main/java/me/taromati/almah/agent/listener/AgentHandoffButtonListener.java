package me.taromati.almah.agent.listener;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.chat.ChatSessionManager;
import me.taromati.almah.agent.chat.HandoffResumptionHandler;
import me.taromati.almah.agent.task.TaskStatus;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.core.messenger.*;
import me.taromati.almah.core.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * HANDOFF "이어하기" 버튼 영구 리스너.
 * 버튼 ID 형식: agent-resume:{taskId 앞 8자}
 *
 * <p>채널 lock(ChatSessionManager.tryAcquireOrQueue)을 거쳐 동시 실행을 방지한다.
 * busy 채널이면 마커 프로토콜로 큐잉되어 인터럽트 루프에서 처리된다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentHandoffButtonListener implements InteractionHandler {

    private static final String RESUME_PREFIX = "agent-resume:";

    private final HandoffResumptionHandler handoffResumptionHandler;
    private final ChatSessionManager chatSessionManager;
    private final TaskStoreService taskStoreService;
    private final AgentListener agentListener;

    public AgentHandoffButtonListener(
            HandoffResumptionHandler handoffResumptionHandler,
            ChatSessionManager chatSessionManager,
            TaskStoreService taskStoreService,
            AgentListener agentListener
    ) {
        this.handoffResumptionHandler = handoffResumptionHandler;
        this.chatSessionManager = chatSessionManager;
        this.taskStoreService = taskStoreService;
        this.agentListener = agentListener;
    }

    @Override
    public String getActionIdPrefix() {
        return RESUME_PREFIX;
    }

    @Override
    public void handle(ActionEvent event, InteractionResponder responder) {
        String actionId = event.actionId();
        if (!actionId.startsWith(RESUME_PREFIX)) return;

        String taskIdPrefix = actionId.substring(RESUME_PREFIX.length());

        // HANDOFF task 검증
        var ctxOpt = handoffResumptionHandler.findByIdPrefix(taskIdPrefix);
        if (ctxOpt.isEmpty()) {
            responder.replyEphemeral("해당 작업을 찾을 수 없습니다.");
            return;
        }

        var ctx = ctxOpt.get();

        // PENDING 상태 확인
        var task = taskStoreService.findById(ctx.taskId()).orElse(null);
        if (task == null || !TaskStatus.PENDING.equals(task.getStatus())) {
            responder.replyEphemeral("이미 처리된 작업입니다.");
            return;
        }

        // 즉시 응답 + 원본 메시지 버튼 제거
        responder.replyEphemeral("📋 이전 작업 '" + StringUtils.truncate(ctx.title(), 50) + "'을 이어서 진행합니다.");
        responder.removeComponents();

        // 채널 lock 통합: 마커 프로토콜로 tryAcquireOrQueue
        ChannelRef channel = event.channel();
        String channelId = channel.channelId();
        String marker = "[HANDOFF_RESUME:" + taskIdPrefix + "]";

        if (!chatSessionManager.tryAcquireOrQueue(channelId, marker)) {
            // busy — 마커가 큐잉됨, 현재 처리가 취소된 후 인터럽트 루프에서 처리
            return;
        }

        // idle — 직접 실행
        agentListener.chatExecutor.submit(() -> handleResumeLoop(channel, ctx, channelId));
    }

    private void handleResumeLoop(ChannelRef channel,
                                   HandoffResumptionHandler.ResumptionContext ctx,
                                   String channelId) {
        agentListener.executeWithTypingIndicator(channel, () -> {
            try {
                // 첫 턴: HANDOFF resume
                var result = agentListener.processResumeTurn(channel, ctx, channelId);
                if (result != null && !result.isCancelled() && !result.roundsExhausted()) {
                    handoffResumptionHandler.markCompleted(ctx.taskId());
                }

                // 인터럽트 루프 (AgentListener.handleChatLoop과 동일 구조)
                while (true) {
                    var pending = chatSessionManager.drainPendingOrRelease(channelId);
                    if (pending.isEmpty()) break;
                    chatSessionManager.clearCancel(channelId);
                    String combined = String.join("\n", pending);
                    agentListener.processSingleTurn(channel, combined, channelId);
                }
            } catch (Exception e) {
                chatSessionManager.forceRelease(channelId);
                throw e;
            }
        });
    }
}
