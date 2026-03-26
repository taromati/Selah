package me.taromati.almah.agent.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.task.TaskStatus;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.core.messenger.*;
import me.taromati.almah.core.util.UUIDv7;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 에스컬레이션 서비스.
 * 승인 요청 생성 + 메신저 알림. 승인/거부 처리 -> TaskStoreService 상태 전이 위임.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class EscalationService {

    private static final int CHAT_APPROVAL_TIMEOUT_MINUTES = 2;

    private final TaskStoreService taskStoreService;
    private final AgentConfigProperties agentConfig;
    private final MessengerGatewayRegistry messengerRegistry;

    /**
     * 대화 모드: 즉시 메신저 버튼으로 승인 요청 (2분 대기).
     *
     * @return true=승인, false=거부, null=타임아웃
     */
    public Boolean requestChatApproval(ChannelRef channel, String toolName,
                                        String description, AgentTaskItemEntity taskItem) {
        String text = String.format("\uD83D\uDD12 **%s** 승인 요청\n> %s", toolName, description);
        InteractiveMessage message = new InteractiveMessage(text, List.of(
                new InteractiveMessage.Action("agent-approve-yes", "허용", InteractiveMessage.ActionStyle.SUCCESS),
                new InteractiveMessage.Action("agent-approve-no", "거부", InteractiveMessage.ActionStyle.DANGER)
        ));

        InteractiveMessageHandle handle = messengerRegistry.sendInteractive(channel, message);
        if (handle == null) return null;

        ActionEvent event = handle.waitForAction(CHAT_APPROVAL_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        if (event == null) {
            handle.editText(text + "\n⏰ 시간 초과");
            log.info("[EscalationService] Chat approval timed out for {} (task={})",
                    toolName, taskItem != null ? taskItem.getId() : "none");
            return null;
        }

        boolean approved = "agent-approve-yes".equals(event.actionId());
        handle.editText(text + (approved ? "\n\u2705 허용되었습니다." : "\n\u274C 거부되었습니다."));

        if (approved) {
            log.info("[EscalationService] Chat approval granted for {} (task={})",
                    toolName, taskItem != null ? taskItem.getId() : "none");
            return true;
        }

        log.info("[EscalationService] Chat approval denied for {} (task={})",
                toolName, taskItem != null ? taskItem.getId() : "none");
        return false;
    }

    /**
     * 루틴 모드: 에스컬레이션 (할 일을 WAITING_APPROVAL로 전환 + 메신저 알림).
     */
    public void escalateForRoutine(AgentTaskItemEntity taskItem, String toolName,
                                    String argumentsJson) {
        String requestId = UUIDv7.generate();

        // 할 일을 WAITING_APPROVAL로 전환
        taskStoreService.transition(taskItem.getId(), TaskStatus.WAITING_APPROVAL);

        // 승인 요청 정보 DB 영속화
        taskStoreService.updateApprovalRequest(taskItem.getId(), requestId, LocalDateTime.now());

        // 메신저 버튼 포함 알림 (비차단)
        String taskIdShort = taskItem.getId().length() > 8
                ? taskItem.getId().substring(0, 8) : taskItem.getId();
        String toolNameShort = toolName.length() > 30
                ? toolName.substring(0, 30) : toolName;
        String notification = String.format(
                "\uD83D\uDD14 **승인 요청** [%s]\n> 할 일: %s\n> 도구: `%s`\n> 버튼을 눌러 승인 또는 거부해주세요.",
                taskIdShort, taskItem.getTitle(), toolName);

        String approveId = "agent-escalation-approve:" + taskIdShort + ":" + toolNameShort;
        String denyId = "agent-escalation-deny:" + taskIdShort + ":" + toolNameShort;
        InteractiveMessage message = new InteractiveMessage(notification, List.of(
                new InteractiveMessage.Action(approveId, "승인", InteractiveMessage.ActionStyle.SUCCESS),
                new InteractiveMessage.Action(denyId, "거부", InteractiveMessage.ActionStyle.DANGER)
        ));

        // broadcastText 대신 resolveChannel + sendInteractive 사용 (버튼이 필요하므로)
        sendAndStoreMessageId(taskItem, message);

        log.info("[EscalationService] Escalated task {} for tool {} (requestId={})",
                taskItem.getId(), toolName, requestId);
    }

    /**
     * 리마인더용: 새 에스컬레이션 메시지 재전송 + messageId DB 갱신.
     * WAITING_APPROVAL 전환은 하지 않음 (이미 해당 상태).
     */
    public void resendEscalationMessage(AgentTaskItemEntity taskItem) {
        String taskIdShort = taskItem.getId().length() > 8
                ? taskItem.getId().substring(0, 8) : taskItem.getId();
        String notification = String.format(
                "⏰ **승인 리마인더** [%s]\n> 할 일: %s\n> 버튼을 눌러 승인 또는 거부해주세요.",
                taskIdShort, taskItem.getTitle());

        String approveId = "agent-escalation-approve:" + taskIdShort + ":resend";
        String denyId = "agent-escalation-deny:" + taskIdShort + ":resend";
        InteractiveMessage message = new InteractiveMessage(notification, List.of(
                new InteractiveMessage.Action(approveId, "승인", InteractiveMessage.ActionStyle.SUCCESS),
                new InteractiveMessage.Action(denyId, "거부", InteractiveMessage.ActionStyle.DANGER)
        ));

        sendAndStoreMessageId(taskItem, message);
        log.info("[EscalationService] Resent escalation message for task {}", taskItem.getId());
    }

    private void sendAndStoreMessageId(AgentTaskItemEntity taskItem, InteractiveMessage message) {
        for (MessengerGateway gw : messengerRegistry.getAllGateways()) {
            ChannelRef ch = gw.resolveChannel(agentConfig.getChannelName());
            if (ch != null) {
                InteractiveMessageHandle handle = messengerRegistry.sendInteractive(ch, message);
                if (handle != null) {
                    taskStoreService.updateEscalationMessageInfo(
                            taskItem.getId(), ch.serialize(), handle.getMessageId());
                    break;
                }
            }
        }
    }

    /**
     * 승인 응답 처리 (외부에서 호출).
     */
    public void handleApprovalResponse(String taskId, boolean approved, String toolName,
                                        ActionScopeFactory scopeFactory) {
        // taskId가 짧은 prefix일 수 있으므로 prefix 검색도 시도
        var optItem = taskStoreService.findById(taskId);
        if (optItem.isEmpty()) {
            optItem = taskStoreService.findByIdPrefix(taskId);
        }
        optItem.ifPresent(item -> {
            item.setApprovalRespondedAt(LocalDateTime.now());
            item.setApprovalResponse(approved ? "APPROVED" : "DENIED");

            if (approved) {
                // Scope 확장
                String expandedScope = scopeFactory.expandScope(item.getActionScope(), toolName);
                taskStoreService.updateActionScope(item.getId(), expandedScope);
                // PENDING으로 복원 (다음 루틴에서 재시도)
                taskStoreService.transition(item.getId(), TaskStatus.PENDING);
                log.info("[EscalationService] Approval granted for task {}, tool {}", taskId, toolName);
            } else {
                // rejectedTools에 추가 + retryCount 증가
                taskStoreService.addRejectedTool(item.getId(), toolName);
                taskStoreService.incrementRetryCount(item.getId());

                // maxRetries 초과 시 FAILED
                var updated = taskStoreService.findById(item.getId()).orElse(item);
                if (updated.getRetryCount() >= updated.getMaxRetries()) {
                    taskStoreService.transition(item.getId(), TaskStatus.FAILED);
                    log.info("[EscalationService] 재시도 한도 초과 → FAILED: task={}", item.getId());
                    notifyFailed(item, toolName);
                } else {
                    taskStoreService.transition(item.getId(), TaskStatus.PENDING);
                    log.info("[EscalationService] Approval denied for task {}, tool {}", taskId, toolName);
                }
            }
        });
    }

    private void notifyFailed(AgentTaskItemEntity item, String toolName) {
        String msg = String.format(
                "\u274C **[할 일 실패]** %s\n> 도구 `%s` 거부 반복 (재시도 %d/%d 초과)",
                item.getTitle(), toolName, item.getRetryCount(), item.getMaxRetries());
        messengerRegistry.broadcastText(agentConfig.getChannelName(), msg);
    }
}
