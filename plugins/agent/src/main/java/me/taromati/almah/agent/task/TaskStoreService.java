package me.taromati.almah.agent.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.db.repository.AgentTaskItemRepository;
import me.taromati.almah.agent.permission.ActionScopeFactory;
import me.taromati.almah.agent.routine.ExecutionLogEntry;
import me.taromati.almah.agent.routine.ExecutionLogSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class TaskStoreService {

    private final AgentTaskItemRepository taskRepository;
    private final AgentConfigProperties agentConfig;

    @Autowired(required = false)
    private ActionScopeFactory actionScopeFactory;

    @Autowired(required = false)
    private ExecutionLogSerializer executionLogSerializer;

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity create(String title, String description, String source) {
        return create(title, description, null, source);
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity create(String title, String description, String originalRequest, String source) {
        return create(title, description, originalRequest, source, agentConfig.getTask().getMaxRetry());
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity create(String title, String description, String originalRequest, String source, int maxRetries) {
        String scope;
        if (TaskSource.CHAT.equals(source) || TaskSource.HANDOFF.equals(source)) {
            scope = actionScopeFactory != null ? actionScopeFactory.createChatScope() : null;
        } else if (TaskSource.SUGGEST.equals(source) || TaskSource.ROUTINE.equals(source) || TaskSource.CRON.equals(source)) {
            scope = actionScopeFactory != null ? actionScopeFactory.createRoutineScope() : null;
        } else {
            scope = actionScopeFactory != null ? actionScopeFactory.createDefaultScope() : null;
        }

        AgentTaskItemEntity item = AgentTaskItemEntity.builder()
                .title(title)
                .description(description)
                .originalRequest(originalRequest)
                .source(source)
                .status(TaskStatus.PENDING)
                .actionScope(scope)
                .maxRetries(maxRetries)
                .build();
        item = taskRepository.save(item);
        log.info("[TaskStore] Created task: {} [{}] source={} maxRetries={}", title, item.getId(), source, maxRetries);
        return item;
    }

    public Optional<AgentTaskItemEntity> findById(String id) {
        return taskRepository.findById(id);
    }

    public Optional<AgentTaskItemEntity> findByIdPrefix(String idPrefix) {
        return taskRepository.findFirstByIdStartingWith(idPrefix);
    }

    public List<AgentTaskItemEntity> findByStatus(String status) {
        return taskRepository.findByStatusOrderByCreatedAtAsc(status);
    }

    public List<AgentTaskItemEntity> findPending() {
        return taskRepository.findByStatusOrderByCreatedAtAsc(TaskStatus.PENDING);
    }

    public List<AgentTaskItemEntity> findActive() {
        return taskRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.WAITING_APPROVAL));
    }

    public List<AgentTaskItemEntity> findAll() {
        return taskRepository.findByStatusNotInOrderByCreatedAtDesc(
                List.of(TaskStatus.CANCELLED));
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity transition(String id, String newStatus) {
        AgentTaskItemEntity item = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));

        TaskStatusMachine.validate(item.getStatus(), newStatus);

        String oldStatus = item.getStatus();
        item.setStatus(newStatus);

        // Side effects
        if (TaskStatus.COMPLETED.equals(newStatus) || TaskStatus.FAILED.equals(newStatus)
                || TaskStatus.CANCELLED.equals(newStatus)) {
            item.setCompletedAt(LocalDateTime.now());
        }
        // FAILED->PENDING 재시도 시 rejectedTools 초기화 (재에스컬레이션 허용)
        if (TaskStatus.FAILED.equals(oldStatus) && TaskStatus.PENDING.equals(newStatus)) {
            item.setRejectedTools(null);
        }

        item = taskRepository.save(item);
        log.info("[TaskStore] Transition: {} [{}] {} -> {}", item.getTitle(), id, oldStatus, newStatus);
        return item;
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity updateProgress(String id, String progress) {
        AgentTaskItemEntity item = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        item.setProgress(progress);
        return taskRepository.save(item);
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity updateActionScope(String id, String actionScopeJson) {
        AgentTaskItemEntity item = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        item.setActionScope(actionScopeJson);
        return taskRepository.save(item);
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity addRejectedTool(String id, String toolName) {
        AgentTaskItemEntity item = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        String current = item.getRejectedTools();
        if (current == null || current.isBlank()) {
            item.setRejectedTools(toolName);
        } else if (!current.contains(toolName)) {
            item.setRejectedTools(current + "," + toolName);
        }
        return taskRepository.save(item);
    }

    public int countPending() {
        return taskRepository.countByStatus(TaskStatus.PENDING);
    }

    public boolean existsActiveByTitle(String title) {
        return taskRepository.existsByTitleAndStatusIn(title,
                List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS));
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity incrementRetryCount(String id) {
        AgentTaskItemEntity item = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        item.setRetryCount(item.getRetryCount() + 1);
        return taskRepository.save(item);
    }

    @Transactional("agentTransactionManager")
    public AgentTaskItemEntity updateApprovalRequest(String id, String requestId, LocalDateTime requestedAt) {
        AgentTaskItemEntity item = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        item.setApprovalRequestId(requestId);
        item.setApprovalRequestedAt(requestedAt);
        return taskRepository.save(item);
    }

    @Transactional("agentTransactionManager")
    public void updateEscalationMessageInfo(String taskId, String channelId, String messageId) {
        taskRepository.findById(taskId).ifPresent(item -> {
            item.setEscalationMessageId(messageId);
            item.setEscalationChannelId(channelId);
            taskRepository.save(item);
        });
    }

    public boolean existsWaitingApprovalForTool(String toolName) {
        return findByStatus(TaskStatus.WAITING_APPROVAL).stream()
                .anyMatch(t -> t.getTitle() != null && t.getTitle().contains("[" + toolName + "]"));
    }

    public List<AgentTaskItemEntity> findTerminalBefore(LocalDateTime before) {
        return taskRepository.findByStatusInAndCompletedAtBefore(
                List.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED), before);
    }

    @Transactional("agentTransactionManager")
    public void delete(String id) {
        taskRepository.deleteById(id);
    }

    @Transactional("agentTransactionManager")
    public void appendExecutionLog(String taskId, ExecutionLogEntry entry) {
        AgentTaskItemEntity item = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (executionLogSerializer == null) return;
        String updated = executionLogSerializer.append(item.getExecutionLog(), entry, 5);
        item.setExecutionLog(updated);
        taskRepository.save(item);
    }

    public List<ExecutionLogEntry> getExecutionLog(String taskId) {
        if (executionLogSerializer == null) return List.of();
        return taskRepository.findById(taskId)
                .map(item -> executionLogSerializer.deserialize(item.getExecutionLog()))
                .orElse(List.of());
    }

    public int countTodayExplorationTasks() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return (int) taskRepository.findByStatusOrderByCreatedAtAsc(TaskStatus.PENDING).stream()
                .filter(t -> TaskSource.ROUTINE.equals(t.getSource()))
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(startOfDay))
                .count()
                + (int) taskRepository.findByStatusInOrderByCreatedAtAsc(
                        List.of(TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS)).stream()
                .filter(t -> TaskSource.ROUTINE.equals(t.getSource()))
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(startOfDay))
                .count();
    }
}
