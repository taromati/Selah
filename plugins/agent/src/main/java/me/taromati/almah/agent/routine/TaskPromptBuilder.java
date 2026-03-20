package me.taromati.almah.agent.routine;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.task.AuditLogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Task 실행 프롬프트 생성.
 * execution_log, progress, audit log를 반영하여 LLM에 전달할 프롬프트를 구성.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class TaskPromptBuilder {

    private final ExecutionLogSerializer executionLogSerializer;
    private final AuditLogService auditLogService;

    public String build(AgentTaskItemEntity task) {
        StringBuilder sb = new StringBuilder();
        sb.append("[루틴 실행] 다음 할 일을 처리하세요.\n\n");
        sb.append("## 제목\n").append(task.getTitle()).append("\n\n");
        if (task.getDescription() != null) {
            sb.append("## 설명\n").append(task.getDescription()).append("\n\n");
        }
        if (task.getOriginalRequest() != null && !task.getOriginalRequest().isBlank()) {
            sb.append("## 원본 요청\n").append(task.getOriginalRequest()).append("\n\n");
        }

        // execution_log 우선, 없으면 progress 폴백
        List<ExecutionLogEntry> executionLog = executionLogSerializer.deserialize(task.getExecutionLog());
        if (!executionLog.isEmpty()) {
            sb.append("## 이전 실행 기록\n");
            sb.append(executionLogSerializer.toMarkdown(executionLog));
        } else if (task.getProgress() != null) {
            sb.append("## 이전 진행 상황\n").append(task.getProgress()).append("\n\n");
        }

        if (task.getRejectedTools() != null && !task.getRejectedTools().isBlank()) {
            sb.append("## 사용 불가 도구\n").append(task.getRejectedTools()).append("\n\n");
            sb.append("⚠️ 위 도구가 거부되었습니다. 대안 도구나 다른 접근법을 시도하세요.\n\n");
        }

        // 감사 로그: 최근 10건 이력
        var auditLogs = auditLogService.findByTaskItem(task.getId());
        if (!auditLogs.isEmpty()) {
            sb.append("## 이전 실행 이력\n");
            int limit = Math.min(auditLogs.size(), 10);
            for (int i = 0; i < limit; i++) {
                var entry = auditLogs.get(i);
                sb.append("- `").append(entry.getToolName()).append("`");
                if (entry.getScopeVerdict() != null) {
                    sb.append(" [").append(entry.getScopeVerdict()).append("]");
                }
                if (entry.getResultSummary() != null) {
                    String summary = entry.getResultSummary().length() > 100
                            ? entry.getResultSummary().substring(0, 100) + "..."
                            : entry.getResultSummary();
                    sb.append(": ").append(summary);
                }
                sb.append("\n");
            }
            if (auditLogs.size() > 10) {
                sb.append("- ... 외 ").append(auditLogs.size() - 10).append("건\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
