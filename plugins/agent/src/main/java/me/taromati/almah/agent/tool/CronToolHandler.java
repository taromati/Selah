package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentScheduledJobEntity;
import me.taromati.almah.agent.db.repository.AgentScheduledJobRepository;
import me.taromati.almah.agent.service.ScheduleCalculator;
import me.taromati.almah.agent.service.ScheduleParser;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.core.util.TimeConstants;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * cron 도구: 예약 잡 관리 (add/list/update/remove/run).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class CronToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("cron")
                            .description("예약 잡 관리")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "action", Map.of(
                                                    "type", "string",
                                                    "enum", List.of("add", "list", "update", "remove", "run"),
                                                    "description", "작업"
                                            ),
                                            "name", Map.of("type", "string", "description", "잡 이름 (add)"),
                                            "when", Map.of("type", "string",
                                                    "description", "실행 시점. 자연어 시각(21시, 21:00), 분 간격(30, 30분), cron(0 9 * * *), ISO(2026-02-15T21:00:00+09:00)"),
                                            "execution_type", Map.of(
                                                    "type", "string",
                                                    "enum", List.of("message", "agent-turn"),
                                                    "description", "message=텍스트, agent-turn=LLM+도구"
                                            ),
                                            "payload", Map.of("type", "string", "description", "실행 내용"),
                                            "job_id", Map.of("type", "string", "description", "잡 전체 ID (update/remove/run)"),
                                            "enabled", Map.of("type", "boolean", "description", "활성화 (update)"),
                                            "max_duration", Map.of("type", "integer", "description", "agent-turn 최대 소요 시간(분). 기본 5분. 복잡한 잡은 10~15 권장.")
                                    ),
                                    "required", List.of("action")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final AgentScheduledJobRepository jobRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void register() {
        toolRegistry.register("cron", DEFINITION, this::execute);
    }

    @SuppressWarnings("unchecked")
    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String action = (String) args.get("action");

            if (action == null) {
                return ToolResult.text("action이 필요합니다 (add/list/update/remove/run)");
            }

            return switch (action) {
                case "add" -> handleAdd(args);
                case "list" -> handleList();
                case "update" -> handleUpdate(args);
                case "remove" -> handleRemove(args);
                case "run" -> handleRun(args);
                default -> ToolResult.text("알 수 없는 action: " + action);
            };
        } catch (Exception e) {
            log.error("[CronToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("cron 오류: " + e.getMessage());
        }
    }

    private ToolResult handleAdd(Map<String, Object> args) {
        String name = (String) args.get("name");
        String when = (String) args.get("when");
        String executionType = args.getOrDefault("execution_type", "agent-turn").toString();
        String payload = (String) args.get("payload");

        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다.");
        if (when == null || when.isBlank()) return ToolResult.text("when이 필요합니다.");
        if (payload == null || payload.isBlank()) return ToolResult.text("payload가 필요합니다.");

        ScheduleParser.ParsedSchedule schedule;
        try {
            schedule = ScheduleParser.parse(when, TimeConstants.KST.getId());
        } catch (IllegalArgumentException e) {
            return ToolResult.text("스케줄 파싱 오류: " + e.getMessage());
        }
        String scheduleType = schedule.type();
        String scheduleValue = schedule.value();

        // 채널 ID
        AgentToolContext ctx = AgentToolContext.get();
        String channelId = ctx != null ? ctx.channelId() : null;
        if (channelId == null) {
            return ToolResult.text("채널 정보를 확인할 수 없습니다.");
        }

        // nextRunAt 계산
        Instant nextRunAt;
        try {
            nextRunAt = ScheduleCalculator.calculateInitialRun(scheduleType, scheduleValue, null);
        } catch (Exception e) {
            return ToolResult.text("스케줄 계산 오류: " + e.getMessage());
        }

        Integer maxDuration = args.containsKey("max_duration")
                ? ((Number) args.get("max_duration")).intValue() : null;

        var job = AgentScheduledJobEntity.builder()
                .name(name)
                .channelId(channelId)
                .scheduleType(scheduleType)
                .scheduleValue(scheduleValue)
                .timezone(TimeConstants.KST.getId())
                .executionType(executionType)
                .payload(payload)
                .enabled(true)
                .nextRunAt(nextRunAt)
                .maxDurationMinutes(maxDuration)
                .consecutiveErrors(0)
                .build();

        jobRepository.save(job);
        log.info("[CronToolHandler] 잡 생성: name={}, type={}, schedule={}({}), nextRun={}",
                name, executionType, scheduleType, scheduleValue, formatInstant(nextRunAt));

        return ToolResult.text(String.format("잡 생성됨\n- ID: %s\n- 이름: %s\n- 스케줄: %s (%s)\n- 실행: %s\n- 다음 실행: %s",
                job.getId(), name, scheduleType, scheduleValue, executionType, formatInstant(nextRunAt)));
    }

    private ToolResult handleList() {
        AgentToolContext ctx = AgentToolContext.get();
        String channelId = ctx != null ? ctx.channelId() : null;

        List<AgentScheduledJobEntity> jobs;
        if (channelId != null) {
            jobs = jobRepository.findByChannelId(channelId);
        } else {
            jobs = jobRepository.findAll();
        }

        if (jobs.isEmpty()) {
            return ToolResult.text("등록된 잡이 없습니다.");
        }

        StringBuilder sb = new StringBuilder("예약 잡 목록:\n");
        for (var job : jobs) {
            sb.append(String.format("\n[%s] %s\n  스케줄: %s (%s) | 실행: %s | %s\n  다음: %s | 마지막: %s (%s)\n  payload: %s\n",
                    job.getId(),
                    job.getName(),
                    job.getScheduleType(), job.getScheduleValue(),
                    job.getExecutionType(),
                    job.getEnabled() ? "활성" : "비활성",
                    job.getNextRunAt() != null ? formatInstant(job.getNextRunAt()) : "-",
                    job.getLastRunAt() != null ? formatInstant(job.getLastRunAt()) : "-",
                    job.getLastRunStatus() != null ? job.getLastRunStatus() : "-",
                    StringUtils.truncate(job.getPayload(), 80)));
            if (job.getMaxDurationMinutes() != null) {
                sb.append("  최대 소요: ").append(job.getMaxDurationMinutes()).append("분\n");
            }
            if (job.getLastError() != null) {
                sb.append("  에러: ").append(StringUtils.truncate(job.getLastError(), 100)).append("\n");
            }
        }

        return ToolResult.text(sb.toString().trim());
    }

    private ToolResult handleUpdate(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isBlank()) return ToolResult.text("job_id가 필요합니다.");

        var job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return ToolResult.text("잡을 찾을 수 없습니다: " + jobId);
        boolean scheduleChanged = false;

        if (args.containsKey("name")) {
            job.setName((String) args.get("name"));
        }
        if (args.containsKey("when")) {
            String when = (String) args.get("when");
            ScheduleParser.ParsedSchedule schedule;
            try {
                schedule = ScheduleParser.parse(when, job.getTimezone());
            } catch (IllegalArgumentException e) {
                return ToolResult.text("스케줄 파싱 오류: " + e.getMessage());
            }
            job.setScheduleType(schedule.type());
            job.setScheduleValue(schedule.value());
            job.setEnabled(true);
            job.setConsecutiveErrors(0);
            scheduleChanged = true;
        }
        if (args.containsKey("payload")) {
            job.setPayload((String) args.get("payload"));
        }
        if (args.containsKey("max_duration")) {
            job.setMaxDurationMinutes(((Number) args.get("max_duration")).intValue());
        }
        if (args.containsKey("enabled")) {
            boolean newEnabled = Boolean.parseBoolean(args.get("enabled").toString());
            if (newEnabled && !job.getEnabled()) {
                scheduleChanged = true; // 비활성 → 활성 전환 시 nextRunAt 재계산
            }
            job.setEnabled(newEnabled);
            if (newEnabled) {
                job.setConsecutiveErrors(0); // 재활성화 시 에러 카운트 리셋
            }
        }

        if (scheduleChanged) {
            Instant nextRun = ScheduleCalculator.calculateNextRun(
                    job.getScheduleType(), job.getScheduleValue(), job.getTimezone(), Instant.now());
            if (nextRun == null && !"at".equals(job.getScheduleType())) {
                return ToolResult.text("다음 실행 시각을 계산할 수 없습니다.");
            }
            job.setNextRunAt(nextRun != null ? nextRun :
                    ScheduleCalculator.calculateInitialRun(job.getScheduleType(), job.getScheduleValue(), job.getTimezone()));
        }

        jobRepository.save(job);
        log.info("[CronToolHandler] 잡 수정: {} ({})", job.getName(), job.getId());

        return ToolResult.text(String.format("잡 수정됨: %s (%s)\n- %s | %s (%s) | 다음: %s",
                job.getName(), job.getId(),
                job.getEnabled() ? "활성" : "비활성",
                job.getScheduleType(), job.getScheduleValue(),
                job.getNextRunAt() != null ? formatInstant(job.getNextRunAt()) : "-"));
    }

    private ToolResult handleRemove(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isBlank()) return ToolResult.text("job_id가 필요합니다.");

        var job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return ToolResult.text("잡을 찾을 수 없습니다: " + jobId);
        jobRepository.delete(job);
        log.info("[CronToolHandler] 잡 삭제: {} ({})", job.getName(), job.getId());

        return ToolResult.text("잡 삭제됨: " + job.getName() + " (" + job.getId() + ")");
    }

    private ToolResult handleRun(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isBlank()) return ToolResult.text("job_id가 필요합니다.");

        var job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return ToolResult.text("잡을 찾을 수 없습니다: " + jobId);
        job.setNextRunAt(Instant.now());
        job.setEnabled(true);
        jobRepository.save(job);
        log.info("[CronToolHandler] 잡 즉시 실행 예약: {} ({})", job.getName(), job.getId());

        return ToolResult.text("잡 즉시 실행 예약됨: " + job.getName() + " (다음 폴링 시 실행)");
    }

    private static String formatInstant(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TimeConstants.KST)
                .format(DateTimeFormatter.ofPattern("M/d H:mm"));
    }
}
