package me.taromati.almah.agent.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentScheduledJobEntity;
import me.taromati.almah.agent.db.repository.AgentScheduledJobRepository;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.messenger.MessengerPlatform;
import me.taromati.almah.core.util.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 예약 잡 프로세서.
 * 30초 간격으로 due 잡을 폴링하여 실행합니다.
 *
 * <h2>동시성 설계</h2>
 * <ul>
 *   <li>fixedDelay → 폴러 자체는 겹치지 않음</li>
 *   <li>message 잡: 인라인 실행 (즉시 완료)</li>
 *   <li>agent-turn 잡: cronJobExecutor 스레드풀에 비동기 제출</li>
 *   <li>중복 실행 방지: markAsRunning()에서 nextRunAt 미래로 갱신(DB) + runningJobs 체크(in-memory)</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentJobProcessor {

    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final AgentScheduledJobRepository jobRepository;
    private final AgentJobExecutor jobExecutor;
    private final AgentActivityLogService activityLogService;
    private final MessengerGatewayRegistry messengerRegistry;
    private final ObjectProvider<AgentJobProcessor> selfProvider;

    private AgentJobProcessor self() {
        return selfProvider.getObject();
    }

    private final ConcurrentHashMap<String, Future<?>> runningJobs = new ConcurrentHashMap<>();
    private final ExecutorService cronJobExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "cron-job-executor");
        t.setDaemon(true);
        return t;
    });

    public AgentJobProcessor(AgentScheduledJobRepository jobRepository,
                             AgentJobExecutor jobExecutor,
                             AgentActivityLogService activityLogService,
                             MessengerGatewayRegistry messengerRegistry,
                             ObjectProvider<AgentJobProcessor> selfProvider) {
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
        this.activityLogService = activityLogService;
        this.messengerRegistry = messengerRegistry;
        this.selfProvider = selfProvider;
    }

    /**
     * 시작 시 nextRunAt이 NULL인 활성 cron 잡을 복구.
     * 배포/장애로 nextRunAt이 유실된 경우 자동 재계산.
     */
    @PostConstruct
    void recoverNullNextRunAt() {
        var allJobs = jobRepository.findAll();
        for (var job : allJobs) {
            if (job.getEnabled() && job.getNextRunAt() == null
                    && "cron".equals(job.getScheduleType())) {
                Instant nextRun = ScheduleCalculator.calculateNextRun(
                        job.getScheduleType(), job.getScheduleValue(),
                        job.getTimezone(), Instant.now());
                if (nextRun != null) {
                    job.setNextRunAt(nextRun);
                    jobRepository.save(job);
                    log.warn("[AgentJobProcessor] nextRunAt 복구: {} ({}) → {}",
                            job.getName(), job.getId(), nextRun);
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        cronJobExecutor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${plugins.agent.cron.check-interval-ms:30000}")
    public void processJobs() {
        // 완료된 Future 정리
        runningJobs.entrySet().removeIf(e -> e.getValue().isDone());

        var dueJobs = jobRepository.findByEnabledTrueAndNextRunAtBefore(Instant.now());
        for (var job : dueJobs) {
            if (runningJobs.containsKey(job.getId())) {
                continue; // 이미 실행 중 → 스킵
            }

            try {
                if ("agent-turn".equals(job.getExecutionType())) {
                    processJobAsync(job);
                } else {
                    processJobSync(job);
                }
            } catch (Exception e) {
                log.error("[AgentJobProcessor] 잡 실행 실패: {} ({})", job.getName(), job.getId(), e);
            }
        }
    }

    private void processJobSync(AgentScheduledJobEntity job) {
        self().markAsRunning(job);
        AgentJobExecutor.ExecutionResult result = null;
        String errorMsg = null;
        boolean success;
        try {
            result = jobExecutor.execute(job);
            success = true;
        } catch (Exception e) {
            errorMsg = e.getMessage();
            success = false;
        }
        self().completeJob(job, success, errorMsg);
        if (success && result != null && result.text() != null && !result.text().isBlank()) {
            sendToChannel(job, result.text());
        } else if (!success) {
            sendErrorToChannel(job, errorMsg, job.getConsecutiveErrors());
        }
        activityLogService.logCronExecution(job, success,
                success ? (result != null ? result.text() : null) : null,
                result != null ? result.toolsUsed() : null,
                result != null ? result.totalTokens() : 0,
                errorMsg);
    }

    private void processJobAsync(AgentScheduledJobEntity job) {
        self().markAsRunning(job);
        Future<?> future = cronJobExecutor.submit(() -> {
            try {
                AgentJobExecutor.ExecutionResult result = null;
                String errorMsg = null;
                boolean success;
                try {
                    result = jobExecutor.execute(job);
                    success = true;
                } catch (Exception e) {
                    errorMsg = e.getMessage();
                    success = false;
                }
                self().completeJob(job, success, errorMsg);
                if (success && result != null && result.text() != null && !result.text().isBlank()) {
                    sendToChannel(job, result.text());
                } else if (!success) {
                    sendErrorToChannel(job, errorMsg, job.getConsecutiveErrors());
                }
                activityLogService.logCronExecution(job, success,
                        success ? (result != null ? result.text() : null) : null,
                        result != null ? result.toolsUsed() : null,
                        result != null ? result.totalTokens() : 0,
                        errorMsg);
            } finally {
                runningJobs.remove(job.getId());
            }
        });
        runningJobs.put(job.getId(), future);
    }

    @Transactional("agentTransactionManager")
    public void markAsRunning(AgentScheduledJobEntity job) {
        job.setLastRunAt(Instant.now());
        // nextRunAt을 미리 갱신하여 DB 조회에서 제외 (중복 실행 방지)
        Instant nextRun = ScheduleCalculator.calculateNextRun(
                job.getScheduleType(), job.getScheduleValue(), job.getTimezone(), Instant.now());
        if (nextRun == null) {
            job.setEnabled(false);  // one-shot 완료
        } else {
            job.setNextRunAt(nextRun);
        }
        jobRepository.save(job);
    }

    @Transactional("agentTransactionManager")
    public void completeJob(AgentScheduledJobEntity job, boolean success, String errorMsg) {
        // nextRunAt은 이미 markAsRunning()에서 설정됨 → 여기서는 상태만 갱신
        if (success) {
            job.setLastRunStatus("success");
            job.setConsecutiveErrors(0);
            job.setLastError(null);
        } else {
            job.setLastRunStatus("error");
            job.setLastError(StringUtils.truncateRaw(errorMsg, 500));
            job.setConsecutiveErrors(job.getConsecutiveErrors() + 1);
            if (job.getConsecutiveErrors() >= MAX_CONSECUTIVE_ERRORS) {
                job.setEnabled(false);  // 연속 에러 → 비활성화
                log.warn("[AgentJobProcessor] 잡 비활성화 (연속 {}회 에러): {} ({})",
                        MAX_CONSECUTIVE_ERRORS, job.getName(), job.getId());
            }
        }
        jobRepository.save(job);
    }

    private void sendErrorToChannel(AgentScheduledJobEntity job, String errorMsg, int consecutiveErrors) {
        try {
            ChannelRef channel = resolveJobChannel(job);
            StringBuilder sb = new StringBuilder();
            sb.append("❌ **[").append(job.getName()).append("]** 실행 실패: ").append(
                    StringUtils.truncateRaw(errorMsg, 200));
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                sb.append("\n⚠️ 연속 ").append(MAX_CONSECUTIVE_ERRORS).append("회 실패로 자동 비활성화되었습니다.");
            }
            messengerRegistry.sendText(channel, sb.toString());
        } catch (Exception e) {
            log.error("[AgentJobProcessor] 에러 전송 실패: {}", e.getMessage());
        }
    }

    private void sendToChannel(AgentScheduledJobEntity job, String text) {
        try {
            ChannelRef channel = resolveJobChannel(job);
            String prefix = "\uD83D\uDD52 **[" + job.getName() + "]**\n";
            messengerRegistry.sendText(channel, prefix + text);
        } catch (Exception e) {
            log.error("[AgentJobProcessor] 메시지 전송 실패: {}", e.getMessage());
        }
    }

    private ChannelRef resolveJobChannel(AgentScheduledJobEntity job) {
        MessengerPlatform platform = job.getChannelPlatform() != null
                ? MessengerPlatform.valueOf(job.getChannelPlatform())
                : MessengerPlatform.DISCORD;
        return ChannelRef.of(platform, job.getChannelId());
    }
}
