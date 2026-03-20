package me.taromati.almah.agent.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.permission.ActionScopeFactory;
import me.taromati.almah.agent.permission.EscalationService;
import me.taromati.almah.agent.task.TaskStatus;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.core.messenger.ActionEvent;
import me.taromati.almah.core.messenger.InteractionHandler;
import me.taromati.almah.core.messenger.InteractionResponder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 루틴 모드 에스컬레이션 버튼 이벤트 리스너.
 * 버튼 ID 형식: agent-escalation-approve:{taskId(8)}:{toolName(30)}
 *              agent-escalation-deny:{taskId(8)}:{toolName(30)}
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentEscalationButtonListener implements InteractionHandler {

    private static final String ACTION_PREFIX = "agent-escalation-";
    private static final String APPROVE_PREFIX = "agent-escalation-approve:";
    private static final String DENY_PREFIX = "agent-escalation-deny:";
    private static final String APPROVE_ALL_PREFIX = "agent-escalation-approve-all:";
    private static final String DENY_ALL_PREFIX = "agent-escalation-deny-all:";

    private final EscalationService escalationService;
    private final ActionScopeFactory actionScopeFactory;
    private final TaskStoreService taskStoreService;

    @Override
    public String getActionIdPrefix() {
        return ACTION_PREFIX;
    }

    @Override
    public void handle(ActionEvent event, InteractionResponder responder) {
        String actionId = event.actionId();

        // 일괄 승인/거부 처리
        if (actionId.startsWith(APPROVE_ALL_PREFIX) || actionId.startsWith(DENY_ALL_PREFIX)) {
            boolean isApproveAll = actionId.startsWith(APPROVE_ALL_PREFIX);
            handleBatchApproval(responder, isApproveAll);
            return;
        }

        boolean isApprove = actionId.startsWith(APPROVE_PREFIX);
        boolean isDeny = actionId.startsWith(DENY_PREFIX);
        if (!isApprove && !isDeny) return;

        String prefix = isApprove ? APPROVE_PREFIX : DENY_PREFIX;
        String payload = actionId.substring(prefix.length());

        // payload = taskIdShort:toolName
        int colonIdx = payload.indexOf(':');
        if (colonIdx < 0) {
            log.warn("[EscalationButton] Invalid button ID format: {}", actionId);
            responder.replyEphemeral("잘못된 버튼 형식입니다.");
            return;
        }

        String taskIdShort = payload.substring(0, colonIdx);
        String toolName = payload.substring(colonIdx + 1);

        if (isApprove) {
            escalationService.handleApprovalResponse(taskIdShort, true, toolName, actionScopeFactory);
            responder.editMessage(event.actionId() + "\n\u2705 승인되었습니다.");
            log.info("[EscalationButton] Approved: task={}, tool={}", taskIdShort, toolName);
        } else {
            escalationService.handleApprovalResponse(taskIdShort, false, toolName, actionScopeFactory);
            responder.editMessage(event.actionId() + "\n\u274C 거부되었습니다.");
            log.info("[EscalationButton] Denied: task={}, tool={}", taskIdShort, toolName);
        }
    }

    private void handleBatchApproval(InteractionResponder responder, boolean approve) {
        var waitingItems = taskStoreService.findByStatus(TaskStatus.WAITING_APPROVAL);
        int count = 0;
        for (var item : waitingItems) {
            escalationService.handleApprovalResponse(item.getId(), approve, "batch", actionScopeFactory);
            count++;
        }
        String action = approve ? "승인" : "거부";
        responder.replyEphemeral("\u2705 " + count + "건 일괄 " + action + " 완료");
        log.info("[EscalationButton] Batch {}: {} items", action, count);
    }
}
