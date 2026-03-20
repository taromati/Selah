package me.taromati.almah.agent.routine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.chat.ChatTerminationClassifier;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentRoutineHistoryEntity;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.db.repository.AgentRoutineHistoryRepository;
import me.taromati.almah.agent.permission.ActionScopeFactory;
import me.taromati.almah.agent.permission.PermissionGate;
import me.taromati.almah.agent.retrospect.RetrospectiveService;
import me.taromati.almah.agent.service.AgentActivityLogService;
import me.taromati.almah.agent.service.AgentContextBuilder;
import me.taromati.almah.agent.task.AuditLogService;
import me.taromati.almah.agent.task.TaskStatus;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.agent.tool.AgentToolContext;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.tool.ToolCallingService;
import me.taromati.almah.llm.tool.ToolCallingService.TerminationReason;
import me.taromati.almah.llm.tool.ToolExecutionFilter;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PENDING 할 일을 createdAt 순으로 실행, 주기 예산 관리, 진행 상황 저장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class RoutineOrchestrator {

    private final TaskStoreService taskStoreService;
    private final PermissionGate permissionGate;
    private final AgentContextBuilder contextBuilder;
    private final AgentConfigProperties config;
    private final LlmClientResolver clientResolver;
    private final ToolCallingService toolCallingService;
    private final RoutineReporter reporter;
    private final AuditLogService auditLogService;
    private final ActionScopeFactory actionScopeFactory;
    private final ToolRegistry toolRegistry;
    private final AgentActivityLogService activityLogService;
    private final ExecutionLogSerializer executionLogSerializer;
    private final TaskPromptBuilder taskPromptBuilder;

    @Autowired(required = false)
    private MemoryService memoryService;

    @Autowired(required = false)
    private AgentRoutineHistoryRepository routineHistoryRepository;

    @Autowired(required = false)
    private RetrospectiveService retrospectiveService;

    /**
     * 단일 할 일 즉시 실행 (!work 커맨드용).
     */
    public void executeSingleTask(String taskId) {
        var taskOpt = taskStoreService.findById(taskId);
        if (taskOpt.isEmpty()) return;
        var task = taskOpt.get();
        if (!TaskStatus.PENDING.equals(task.getStatus())) return;

        // actionScope 없으면 기본 scope 설정
        if (task.getActionScope() == null || task.getActionScope().isBlank()) {
            String scope = actionScopeFactory.createRoutineScope();
            taskStoreService.updateActionScope(task.getId(), scope);
            task.setActionScope(scope);
        }

        try {
            var report = executeTask(task, 10);
            log.info("[RoutineOrchestrator] 즉시 실행: {} -> {}",
                    task.getTitle(), report.completed() ? "COMPLETED" : "PENDING");
            logReport(report);
        } catch (Exception e) {
            log.error("[RoutineOrchestrator] 즉시 실행 실패: {}", e.getMessage(), e);
            activityLogService.logRoutineExecution(task.getTitle(), false, null, 0, e.getMessage());
        }
    }

    /**
     * 루틴 실행: PENDING 할 일을 순서대로 처리.
     */
    public void executeRoutine() {
        List<AgentTaskItemEntity> pendingTasks = taskStoreService.findPending();

        if (pendingTasks.isEmpty()) {
            log.debug("[Routine] 대기 중인 할 일 없음");
            return;
        }

        int timeBudgetMinutes = config.getRoutine().getActiveWorkMinutes();
        long startTime = System.currentTimeMillis();
        List<RoutineReport> reports = new ArrayList<>();

        for (AgentTaskItemEntity task : pendingTasks) {
            // 시간 예산 초과 확인
            long elapsed = (System.currentTimeMillis() - startTime) / 60000;
            if (elapsed >= timeBudgetMinutes) {
                log.info("[Routine] 시간 예산 초과 ({}분), 나머지 할 일 다음 주기로", elapsed);
                break;
            }

            // actionScope 없으면 routineScope 설정
            if (task.getActionScope() == null || task.getActionScope().isBlank()) {
                String scope = actionScopeFactory.createRoutineScope();
                taskStoreService.updateActionScope(task.getId(), scope);
                task.setActionScope(scope);
            }

            RoutineReport report = executeTask(task, timeBudgetMinutes - (int) elapsed);
            reports.add(report);

            // WAITING_APPROVAL이면 다음 할 일로
            if (TaskStatus.WAITING_APPROVAL.equals(task.getStatus())) continue;
        }

        // 보고 + activity log
        if (!reports.isEmpty()) {
            reporter.report(reports);
            for (var report : reports) {
                logReport(report);
            }
        }
    }

    // package-private for testing
    RoutineReport executeTask(AgentTaskItemEntity task, int remainingMinutes) {
        // IN_PROGRESS 전환
        taskStoreService.transition(task.getId(), TaskStatus.IN_PROGRESS);

        long taskStartTime = System.currentTimeMillis();
        String providerName = config.getRoutineProvider();
        LlmClient client = clientResolver.resolve(providerName);

        // AgentToolContext 설정
        String channelId = resolveChannelId();
        AgentToolContext.set(channelId, false, false, client, providerName, AgentToolContext.ExecutionContext.ROUTINE);

        try {
            // 컨텍스트 구성 (할 일 설명 + 진행 상황)
            String prompt = buildTaskPrompt(task);
            var effective = config.resolveSessionConfig(client.getCapabilities());

            String systemContent = contextBuilder.buildSystemContent(
                    config.getSystemPrompt(), null, client);
            List<ChatMessage> context = List.of(
                    ChatMessage.builder().role("system").content(systemContent).build(),
                    ChatMessage.builder().role("user").content(prompt).build()
            );

            // 도구 목록 (루틴 deny 적용)
            List<String> tools = resolveRoutineTools();

            // 필터 생성
            ToolExecutionFilter filter = permissionGate.createRoutineFilter(task);

            // Tool Calling
            var callingConfig = new ToolCallingService.ToolCallingConfig(
                    effective.contextWindow(), effective.charsPerToken(),
                    Math.min(remainingMinutes, 15));

            SamplingParams params = new SamplingParams(
                    effective.maxTokens(), config.getTemperature(),
                    config.getTopP(), config.getMinP(),
                    config.getFrequencyPenalty(), config.getRepetitionPenalty(), null);

            // 외부 취소 체크: task가 CANCELLED로 전환되면 루프 중단
            String taskId = task.getId();
            var result = toolCallingService.chatWithTools(
                    context, params, tools, filter, client, null, callingConfig,
                    () -> {
                        var current = taskStoreService.findById(taskId).orElse(null);
                        return current != null && TaskStatus.isTerminal(current.getStatus());
                    });

            // 결과 처리
            String response = result.textResponse();
            taskStoreService.updateProgress(task.getId(), response);

            // execution_log 기록
            recordExecutionLog(task, result);

            // 도구 사용 횟수: intermediate messages 중 tool role 카운트
            int toolsUsed = (int) result.intermediateMessages().stream()
                    .filter(m -> "tool".equals(m.getRole()))
                    .count();
            long elapsedMs = System.currentTimeMillis() - taskStartTime;

            if (result.roundsExhausted()) {
                // 예산 소진 → 종료 분류 후 retryCount 증가
                var classification = ChatTerminationClassifier.classify(result);
                int increment = (classification.reason() == ChatTerminationClassifier.ChatExitReason.TOOL_FAILURE) ? 2 : 1;
                for (int i = 0; i < increment; i++) {
                    taskStoreService.incrementRetryCount(task.getId());
                }
                var updated = taskStoreService.findById(task.getId()).orElse(task);
                if (updated.getRetryCount() >= task.getMaxRetries()) {
                    taskStoreService.transition(task.getId(), TaskStatus.FAILED);
                    String failReason = "예산 소진 최종 실패 (" + classification.reason() + ": " + classification.detail()
                            + ", 재시도 " + updated.getRetryCount() + "/" + task.getMaxRetries() + "회)";
                    taskStoreService.updateProgress(task.getId(), response + "\n\n" + failReason);
                    recordEpisode(task, failReason);
                    saveRoutineHistory(task, failReason, "FAILED");
                    triggerTaskRetrospect(task, failReason, false);
                    return new RoutineReport(task, response, false, toolsUsed, elapsedMs);
                }
                taskStoreService.transition(task.getId(), TaskStatus.PENDING);
                log.info("[Routine] 예산 소진 재시도 ({}/{}, reason={}): {}",
                        updated.getRetryCount(), task.getMaxRetries(), classification.reason(), task.getTitle());
                return new RoutineReport(task, response, false, toolsUsed, elapsedMs);
            }

            // 완료 — 에스컬레이션 등으로 이미 상태가 변경된 경우 스킵
            var currentTask = taskStoreService.findById(task.getId()).orElse(null);
            boolean transitioned = false;
            if (currentTask != null && !TaskStatus.isTerminal(currentTask.getStatus())
                    && !TaskStatus.WAITING_APPROVAL.equals(currentTask.getStatus())) {
                taskStoreService.transition(task.getId(), TaskStatus.COMPLETED);
                transitioned = true;
            }
            recordEpisode(task, response);
            saveRoutineHistory(task, response, "COMPLETED");
            triggerTaskRetrospect(task, response, true);
            return new RoutineReport(task, response, transitioned, toolsUsed, elapsedMs);

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - taskStartTime;
            log.error("[Routine] 할 일 실행 실패: {} - {}", task.getId(), e.getMessage());
            taskStoreService.updateProgress(task.getId(), "에러: " + e.getMessage());

            // retryCount 기반 재시도: maxRetries 초과 시만 FAILED
            var updated = taskStoreService.incrementRetryCount(task.getId());
            if (updated.getRetryCount() >= task.getMaxRetries()) {
                taskStoreService.transition(task.getId(), TaskStatus.FAILED);
                String errorMsg = "최종 실패 (재시도 " + updated.getRetryCount() + "/" + task.getMaxRetries() + "회): " + e.getMessage();
                recordEpisode(task, errorMsg);
                saveRoutineHistory(task, errorMsg, "FAILED");
                triggerTaskRetrospect(task, errorMsg, false);
            } else {
                taskStoreService.transition(task.getId(), TaskStatus.PENDING);
                log.info("[Routine] 재시도 대기 ({}/{}): {}", updated.getRetryCount(), task.getMaxRetries(), task.getTitle());
            }
            return new RoutineReport(task, "에러: " + e.getMessage(), false, 0, elapsedMs);
        } finally {
            AgentToolContext.clear();
        }
    }

    private String buildTaskPrompt(AgentTaskItemEntity task) {
        return taskPromptBuilder.build(task);
    }

    private void recordExecutionLog(AgentTaskItemEntity task,
                                      ToolCallingService.ToolCallingResult result) {
        try {
            // intermediateMessages에서 ExecutionLogEntry 생성
            int cycle = executionLogSerializer.deserialize(task.getExecutionLog()).size() + 1;
            ExecutionLogEntry entry = executionLogSerializer.fromToolCallingResult(
                    result.intermediateMessages(), cycle);

            // status 결정
            String status;
            var currentTask = taskStoreService.findById(task.getId()).orElse(null);
            if (currentTask != null && TaskStatus.WAITING_APPROVAL.equals(currentTask.getStatus())) {
                status = "ESCALATED";
            } else if (result.terminationReason() == TerminationReason.COMPLETED) {
                status = "COMPLETED";
            } else {
                status = "PARTIAL";
            }

            var entryWithStatus = new ExecutionLogEntry(
                    entry.cycle(), entry.timestamp(), entry.toolCalls(),
                    entry.llmConclusion(), status);

            taskStoreService.appendExecutionLog(task.getId(), entryWithStatus);
        } catch (Exception e) {
            log.warn("[Routine] execution_log 기록 실패 (progress로 폴백): {}", e.getMessage());
        }
    }

    private List<String> resolveRoutineTools() {
        return permissionGate.getVisibleTools(config.getRoutine().getExcludedTools());
    }

    private void saveRoutineHistory(AgentTaskItemEntity task, String response, String status) {
        if (routineHistoryRepository == null) return;
        try {
            routineHistoryRepository.save(
                    AgentRoutineHistoryEntity.of(task.getTitle(), response, status));
        } catch (Exception e) {
            log.warn("[Routine] History 저장 실패: {}", e.getMessage());
        }
    }

    private void triggerTaskRetrospect(AgentTaskItemEntity task, String response, boolean success) {
        if (retrospectiveService == null) return;
        try {
            // 감사 로그 요약
            var auditLogs = auditLogService.findByTaskItem(task.getId());
            String auditSummary = null;
            if (!auditLogs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int limit = Math.min(auditLogs.size(), 10);
                for (int i = 0; i < limit; i++) {
                    var entry = auditLogs.get(i);
                    sb.append(entry.getToolName());
                    if (entry.getScopeVerdict() != null) sb.append("[").append(entry.getScopeVerdict()).append("]");
                    if (i < limit - 1) sb.append(", ");
                }
                auditSummary = sb.toString();
            }

            String desc = task.getDescription();
            String prog = task.getProgress();
            // 비동기 실행
            String finalAuditSummary = auditSummary;
            Thread.ofVirtual().start(() -> {
                try {
                    retrospectiveService.retrospectTask(
                            task.getTitle(), desc, prog, success, finalAuditSummary);
                } catch (Exception e) {
                    log.warn("[Routine] Task 회고 실패: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[Routine] Task 회고 트리거 실패: {}", e.getMessage());
        }
    }

    private void recordEpisode(AgentTaskItemEntity task, String response) {
        if (memoryService == null) return;
        try {
            String content = "루틴 완료: " + task.getTitle() + "\n결과: " + response;
            memoryService.ingest(content, Map.of(
                    "source", "routine",
                    "taskId", task.getId()));
        } catch (Exception e) {
            log.warn("[Routine] 에피소드 기록 실패: {}", e.getMessage());
        }
    }

    private void logReport(RoutineReport report) {
        try {
            activityLogService.logRoutineExecution(
                    report.task().getTitle(),
                    report.completed(),
                    report.response(),
                    report.toolsUsed(),
                    report.completed() ? null : "미완료");
        } catch (Exception e) {
            log.warn("[Routine] Activity log 저장 실패: {}", e.getMessage());
        }
    }

    private String resolveChannelId() {
        return config.getChannelName();
    }

    public record RoutineReport(AgentTaskItemEntity task, String response, boolean completed,
                                   int toolsUsed, long elapsedMs) {}
}
