package me.taromati.almah.agent.routine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.service.AgentBusyState;
import me.taromati.almah.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 30분 주기 루틴 실행 스케줄러.
 * 24시간 동작, 연속 에러 자동 중단(3회 -> 6주기 재시도), 수동 실행.
 * 디스코드 노티 시간대 제어는 RoutineReporter에서 처리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class RoutineScheduler {

    private final RoutineOrchestrator orchestrator;
    private final AgentConfigProperties config;

    @Autowired(required = false)
    private AgentBusyState busyState;

    @Autowired(required = false)
    private MemoryService memoryService;

    private int consecutiveErrors = 0;
    private int skipCycles = 0;
    private int routineCycleCount = 0;

    @Scheduled(fixedDelayString = "${plugins.agent.routine.interval-ms:1800000}")
    public void scheduledRun() {
        if (!Boolean.TRUE.equals(config.getRoutine().getEnabled())) return;
        if (skipCycles > 0) {
            skipCycles--;
            return;
        }
        if (busyState != null && busyState.isChatBusy()) return;

        if (busyState != null) busyState.setRoutineBusy(true);
        try {
            orchestrator.executeRoutine();
            consecutiveErrors = 0;

            // 매 4번째 주기(~2시간)마다 미처리 에피소드 재시도 (GAP-5)
            routineCycleCount++;
            if (routineCycleCount % 4 == 0 && memoryService != null) {
                try {
                    memoryService.retryUnprocessedChunks();
                } catch (Exception retryEx) {
                    log.warn("[RoutineScheduler] Memory retry failed: {}", retryEx.getMessage());
                }
            }
        } catch (Exception e) {
            consecutiveErrors++;
            if (consecutiveErrors >= 3) {
                skipCycles = 6; // 3시간 스킵 (6 * 30분)
                consecutiveErrors = 0;
                log.error("[RoutineScheduler] 연속 3회 에러 -> 6주기 스킵", e);
            }
        } finally {
            if (busyState != null) busyState.setRoutineBusy(false);
        }
    }

    public void runManual() {
        consecutiveErrors = 0;
        skipCycles = 0;
        orchestrator.executeRoutine();
    }

}
