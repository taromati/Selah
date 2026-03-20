package me.taromati.almah.agent.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class TaskLifecycleManager implements ApplicationRunner {

    private final TaskStoreService taskStoreService;
    private final MessengerGatewayRegistry messengerRegistry;
    private final AgentConfigProperties config;

    /**
     * On startup: recover any IN_PROGRESS items back to PENDING
     */
    @Override
    public void run(ApplicationArguments args) {
        List<AgentTaskItemEntity> inProgress = taskStoreService.findByStatus(TaskStatus.IN_PROGRESS);
        for (AgentTaskItemEntity item : inProgress) {
            try {
                taskStoreService.transition(item.getId(), TaskStatus.PENDING);
                log.info("[TaskLifecycle] Recovered IN_PROGRESS -> PENDING: {}", item.getTitle());
            } catch (Exception e) {
                log.warn("[TaskLifecycle] Failed to recover item {}: {}", item.getId(), e.getMessage());
            }
        }
        if (!inProgress.isEmpty()) {
            log.info("[TaskLifecycle] Recovered {} items from IN_PROGRESS to PENDING", inProgress.size());
        }
    }

    /**
     * Periodic cleanup: expire WAITING_APPROVAL after 24h, remove terminal tasks after 30d
     */
    @Scheduled(fixedDelay = 3600000)
    public void periodicCleanup() {
        remindWaitingApprovals();
        expireWaitingApprovals();
        cleanupTerminalTasks();
    }

    private void remindWaitingApprovals() {
        List<AgentTaskItemEntity> waiting = taskStoreService.findByStatus(TaskStatus.WAITING_APPROVAL);
        int timeoutHours = config.getTask().getApprovalTimeoutHours();
        int reminderCount = config.getTask().getReminderCount();
        int intervalHours = timeoutHours / (reminderCount + 1);

        for (AgentTaskItemEntity item : waiting) {
            if (item.getApprovalRequestedAt() == null) continue;
            long elapsedHours = java.time.Duration.between(item.getApprovalRequestedAt(), LocalDateTime.now()).toHours();
            for (int r = 1; r <= reminderCount; r++) {
                long reminderHour = (long) intervalHours * r;
                if (elapsedHours >= reminderHour && elapsedHours < reminderHour + 1) {
                    long remainingHours = timeoutHours - elapsedHours;
                    messengerRegistry.broadcastText(config.getChannelName(),
                            "\u23F0 **승인 대기 리마인더**: " + item.getTitle()
                            + "\n" + remainingHours + "시간 내 미승인 시 자동 실패 처리됩니다.");
                    break;
                }
            }
        }
    }

    private void expireWaitingApprovals() {
        List<AgentTaskItemEntity> waiting = taskStoreService.findByStatus(TaskStatus.WAITING_APPROVAL);
        LocalDateTime cutoff = LocalDateTime.now().minusHours(config.getTask().getApprovalTimeoutHours());

        for (AgentTaskItemEntity item : waiting) {
            if (item.getApprovalRequestedAt() != null && item.getApprovalRequestedAt().isBefore(cutoff)) {
                try {
                    taskStoreService.transition(item.getId(), TaskStatus.FAILED);
                    log.info("[TaskLifecycle] Expired approval timeout -> FAILED: {}", item.getTitle());

                    messengerRegistry.broadcastText(config.getChannelName(),
                            "\u26A0\uFE0F **승인 만료**: " + item.getTitle()
                            + "\n" + config.getTask().getApprovalTimeoutHours() + "시간 내 미승인으로 자동 실패 처리되었습니다.");
                } catch (Exception e) {
                    log.warn("[TaskLifecycle] Failed to expire item {}: {}", item.getId(), e.getMessage());
                }
            }
        }
    }

    private void cleanupTerminalTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        List<AgentTaskItemEntity> old = taskStoreService.findTerminalBefore(threshold);
        for (AgentTaskItemEntity item : old) {
            taskStoreService.delete(item.getId());
        }
        if (!old.isEmpty()) {
            log.info("[TaskLifecycle] 30일 경과 종단 상태 {} 건 삭제", old.size());
        }
    }
}
