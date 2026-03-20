package me.taromati.almah.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentActivityLogEntity;
import me.taromati.almah.agent.db.entity.AgentScheduledJobEntity;
import me.taromati.almah.agent.db.repository.AgentActivityLogRepository;
import me.taromati.almah.core.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentActivityLogService {

    private final AgentActivityLogRepository repository;

    public void logRoutineExecution(String taskTitle,
                                     boolean success,
                                     String resultText,
                                     int toolsUsed,
                                     String errorMsg) {
        var entity = AgentActivityLogEntity.builder()
                .activityType("routine")
                .jobName(taskTitle)
                .channelId("routine")
                .executionType("routine")
                .status(success ? "success" : "error")
                .resultText(StringUtils.truncateRaw(resultText, 5000))
                .toolsUsed(toolsUsed > 0 ? String.valueOf(toolsUsed) : null)
                .errorMessage(StringUtils.truncateRaw(errorMsg, 500))
                .build();
        try {
            repository.save(entity);
        } catch (Exception e) {
            log.error("[ActivityLog] Routine 로그 저장 실패: {}", e.getMessage());
        }
    }

    public void logCronExecution(AgentScheduledJobEntity job,
                                 boolean success,
                                 String resultText,
                                 List<String> toolsUsed,
                                 int totalTokens,
                                 String errorMsg) {
        var entity = AgentActivityLogEntity.builder()
                .activityType("cron")
                .jobName(job.getName())
                .channelId(job.getChannelId())
                .executionType(job.getExecutionType())
                .status(success ? "success" : "error")
                .resultText(StringUtils.truncateRaw(resultText, 5000))
                .toolsUsed(toJson(toolsUsed))
                .errorMessage(StringUtils.truncateRaw(errorMsg, 500))
                .totalTokens(totalTokens > 0 ? totalTokens : null)
                .build();

        try {
            repository.save(entity);
        } catch (Exception e) {
            log.error("[ActivityLog] Cron 로그 저장 실패: {}", e.getMessage());
        }
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
