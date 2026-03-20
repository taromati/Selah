package me.taromati.almah.agent.routine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.task.TaskSource;
import me.taromati.almah.agent.task.TaskStatus;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.core.util.TimeConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 루틴 주기 결과를 1개 요약 메시지로 Discord에 보고.
 *
 * <p>개선점:
 * <ul>
 *   <li>FAILED 작업에 실패 사유 표시</li>
 *   <li>HANDOFF 원본 요청 표시</li>
 *   <li>재시도 현황 표시</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class RoutineReporter {

    private final MessengerGatewayRegistry messengerRegistry;
    private final AgentConfigProperties config;
    private final TaskStoreService taskStoreService;

    public void report(List<RoutineOrchestrator.RoutineReport> reports) {
        if (reports.isEmpty()) return;
        if (!isWithinNotifyHours()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDC93 **[루틴 보고]**\n");

        for (var report : reports) {
            var task = report.task();
            String icon;
            String statusLabel;

            if (report.completed()) {
                icon = "\u2705";
                statusLabel = "";
            } else if (TaskStatus.WAITING_APPROVAL.equals(task.getStatus())) {
                icon = "\u23F3";
                statusLabel = " [승인 대기]";
            } else if (TaskStatus.FAILED.equals(task.getStatus())) {
                icon = "\u274C";
                statusLabel = " [실패]";
            } else {
                // PENDING (재시도 대기)
                icon = "\uD83D\uDD04";
                statusLabel = " [재시도 " + task.getRetryCount() + "/" + task.getMaxRetries() + "]";
            }

            sb.append(icon).append(" **").append(task.getTitle()).append("**");
            sb.append(statusLabel);
            sb.append(" (").append(formatElapsed(report.elapsedMs()));
            if (report.toolsUsed() > 0) {
                sb.append(", 도구 ").append(report.toolsUsed()).append("회");
            }
            sb.append(")");

            // HANDOFF 원본 요청 표시
            if (TaskSource.HANDOFF.equals(task.getSource()) && task.getOriginalRequest() != null) {
                String original = StringUtils.truncate(task.getOriginalRequest(), 60);
                if (!original.equals(task.getTitle())) {
                    sb.append(" ← `").append(original).append("`");
                }
            }
            sb.append("\n");

            if (report.response() != null) {
                sb.append("> ").append(report.response()).append("\n");
            }
        }

        // 잔여 현황
        int pendingCount = taskStoreService.countPending();
        var failedList = taskStoreService.findByStatus(TaskStatus.FAILED);
        var waitingList = taskStoreService.findByStatus(TaskStatus.WAITING_APPROVAL);

        if (pendingCount > 0 || !failedList.isEmpty() || !waitingList.isEmpty()) {
            sb.append("\n\uD83D\uDCCA ");
            List<String> parts = new java.util.ArrayList<>();
            if (pendingCount > 0) parts.add("대기 " + pendingCount + "건");
            if (!failedList.isEmpty()) parts.add("실패 " + failedList.size() + "건");
            if (!waitingList.isEmpty()) parts.add("승인 대기 " + waitingList.size() + "건");
            sb.append(String.join(" / ", parts));
        }

        messengerRegistry.broadcastText(config.getChannelName(), sb.toString());
    }

    private boolean isWithinNotifyHours() {
        var routine = config.getRoutine();
        int now = ZonedDateTime.now(TimeConstants.KST).getHour();
        int start = routine.getActiveStartHour();
        int end = routine.getActiveEndHour();
        if (start <= end) {
            return now >= start && now < end;
        } else {
            return now >= start || now < end;
        }
    }

    private static String formatElapsed(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "초";
        return (seconds / 60) + "분 " + (seconds % 60) + "초";
    }
}
