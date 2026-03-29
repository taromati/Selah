package me.taromati.almah.agent.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.task.AuditLogService;
import me.taromati.almah.agent.tool.AgentToolContext;
import me.taromati.almah.agent.tool.AgentToolContext.ExecutionContext;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.llm.tool.ToolExecutionFilter;
import me.taromati.almah.llm.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 대화/루틴/제안/Cron/서브에이전트 공통 권한 게이트.
 * 판정 흐름: excludedTools → rejectedTools → ContextPolicyResolver → RejectionStrategy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class PermissionGate {

    private final AgentConfigProperties config;
    private final AuditLogService auditLogService;
    private final ToolRegistry toolRegistry;
    private final RejectionResolver rejectionResolver;
    private final ContextPolicyResolver contextPolicyResolver;
    private final ToolGroupResolver toolGroupResolver;
    private final ActionScopeFactory actionScopeFactory;
    private final ToolPolicyService toolPolicyService;

    public ToolExecutionFilter createChatFilter(AgentTaskItemEntity taskItem, ChannelRef channel) {
        return createFilter(ExecutionContext.CHAT, taskItem, channel,
                null);
    }

    public ToolExecutionFilter createRoutineFilter(AgentTaskItemEntity taskItem) {
        return createFilter(ExecutionContext.ROUTINE, taskItem, null,
                config.getRoutine().getExcludedTools());
    }

    public ToolExecutionFilter createSuggestFilter(AgentTaskItemEntity taskItem) {
        return createFilter(ExecutionContext.SUGGEST, taskItem, null, null);
    }

    public ToolExecutionFilter createCronFilter() {
        return createFilter(ExecutionContext.CRON, null, null,
                config.getCron().getExcludedTools());
    }

    public ToolExecutionFilter createSubagentFilter(List<String> excludedTools) {
        return createFilter(ExecutionContext.SUBAGENT, null, null, excludedTools);
    }

    /**
     * 범용 ToolExecutionFilter 생성.
     * 판정: excludedTools → rejectedTools → ContextPolicyResolver → RejectionStrategy.
     */
    private ToolExecutionFilter createFilter(ExecutionContext context,
                                              AgentTaskItemEntity taskItem,
                                              ChannelRef channel,
                                              List<String> excludedTools) {
        RejectionStrategy rejection = rejectionResolver.resolve(context, channel, taskItem);

        return (toolName, argumentsJson) -> {
            String sessionId = AgentToolContext.get() != null ? AgentToolContext.get().channelId() : null;
            String taskId = taskItem != null ? taskItem.getId() : null;

            // 1~2단계: 구조적 차단 (excludedTools + rejectedTools)
            String rejectedTools = taskItem != null ? taskItem.getRejectedTools() : null;
            var blockVerdict = ActionScopeResolver.resolve(
                    toolName, argumentsJson, excludedTools, rejectedTools, config.getExec());

            if (blockVerdict == ActionScopeResolver.Verdict.DENY) {
                auditLogService.log(taskId, sessionId, toolName, argumentsJson, null, "DENY");
                return String.format("'%s' 도구는 사용이 금지되어 있습니다.", toolName);
            }

            // 3~5단계: 컨텍스트 정책
            var verdict = contextPolicyResolver.resolve(context, toolName);

            if (verdict == ActionScopeResolver.Verdict.ALLOW) {
                // exec 보안 검증은 ExecToolHandler에서 수행 (S09: 단일 검증 지점)
                auditLogService.log(taskId, sessionId, toolName, argumentsJson, null, "ALLOW");
                return null;
            }

            if (verdict == ActionScopeResolver.Verdict.DENY) {
                auditLogService.log(taskId, sessionId, toolName, argumentsJson, null, "DENY");
                return String.format("'%s' 도구는 사용이 금지되어 있습니다.", toolName);
            }

            // ASK → RejectionStrategy 위임
            String result = rejection.onDenied(toolName, argumentsJson, taskItem);
            String logVerdict = result == null ? "ESCALATED_APPROVED" : "ESCALATED_DENIED";
            auditLogService.log(taskId, sessionId, toolName, argumentsJson, result, logVerdict);
            return result;
        };
    }

    /**
     * LLM tools 배열에 포함할 도구 목록.
     * 컨텍스트 allow/escalate → ToolGroupResolver로 확장 → global-deny/excludedTools 제거.
     */
    public List<String> getCoreTools(List<String> excludedTools) {
        var context = AgentToolContext.get() != null
                ? AgentToolContext.get().executionContext()
                : ExecutionContext.CHAT;

        Set<String> allowed = contextPolicyResolver.getAllowedTools(context);
        Set<String> core = toolGroupResolver.expandForLlmToolList(allowed, toolRegistry);

        // global-deny 제거
        config.getTools().getGlobalDeny().forEach(core::remove);
        if (excludedTools != null) core.removeAll(excludedTools);

        return new ArrayList<>(core);
    }

    public List<String> getCoreTools() {
        return getCoreTools(null);
    }

    /**
     * 명시적으로 지정된 도구 목록에서 global-deny + excludedTools 제외.
     */
    public List<String> filterTools(List<String> requestedTools, List<String> excludedTools) {
        Set<String> filtered = new LinkedHashSet<>(requestedTools);
        filtered.removeAll(config.getTools().getGlobalDeny());
        if (excludedTools != null) filtered.removeAll(excludedTools);
        filtered.retainAll(toolRegistry.getRegisteredToolNames());
        return new ArrayList<>(filtered);
    }
}
