package me.taromati.almah.agent.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.task.AuditLogService;
import me.taromati.almah.agent.tool.AgentToolContext;
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
 * 대화/루틴/제안 공통 권한 게이트.
 * ToolPolicyService + ActionScopeResolver 판정 -> 허용 시 감사 로그, 거부 시 RejectionStrategy 위임.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class PermissionGate {

    private final AgentConfigProperties config;
    private final AuditLogService auditLogService;
    private final ToolRegistry toolRegistry;
    private final ChatRejection chatRejection;
    private final RoutineRejection routineRejection;
    private final SuggestRejection suggestRejection;
    private final ActionScopeFactory actionScopeFactory;
    private final ToolPolicyService toolPolicyService;

    /**
     * 대화 모드용 ToolExecutionFilter 생성.
     * 거부 시 Discord 승인 버튼으로 에스컬레이션.
     */
    public ToolExecutionFilter createChatFilter(AgentTaskItemEntity taskItem, ChannelRef channel) {
        RejectionStrategy rejection = chatRejection.withChannel(channel);
        String scopeOverride = (taskItem == null) ? actionScopeFactory.createChatScope() : null;
        return createFilter(taskItem, rejection, scopeOverride, null);
    }

    /**
     * 루틴 모드용 ToolExecutionFilter 생성.
     * 거부 시 WAITING_APPROVAL 전환 + Discord 알림.
     */
    public ToolExecutionFilter createRoutineFilter(AgentTaskItemEntity taskItem) {
        return createFilter(taskItem, routineRejection, null, config.getRoutine().getExcludedTools());
    }

    /**
     * 제안 모드용 ToolExecutionFilter 생성.
     * 거부 시 즉시 에러 반환.
     */
    public ToolExecutionFilter createSuggestFilter(AgentTaskItemEntity taskItem) {
        return createFilter(taskItem, suggestRejection, null, null);
    }

    /**
     * Cron 잡용 ToolExecutionFilter 생성.
     * 거부 시 즉시 에러 반환 (에스컬레이션 없음 — Cron은 AgentTaskItemEntity 없음).
     */
    public ToolExecutionFilter createCronFilter() {
        String cronScope = actionScopeFactory.createCronScope();
        return createFilter(null, suggestRejection, cronScope, config.getCron().getExcludedTools());
    }

    /**
     * 서브에이전트용 ToolExecutionFilter 생성.
     * 거부 시 즉시 에러 반환 (에스컬레이션 없음).
     */
    public ToolExecutionFilter createSubagentFilter(List<String> excludedTools) {
        return createFilter(null, suggestRejection, null, excludedTools);
    }

    /**
     * 범용 ToolExecutionFilter 생성 (scope override + excludedTools 지원).
     */
    private ToolExecutionFilter createFilter(AgentTaskItemEntity taskItem,
                                              RejectionStrategy rejection,
                                              String scopeOverride,
                                              List<String> excludedTools) {
        return (toolName, argumentsJson) -> {
            String sessionId = AgentToolContext.get() != null ? AgentToolContext.get().channelId() : null;

            // per-tool policy 조회
            String policy = toolPolicyService.getPolicy(toolName);
            String rejectedTools = taskItem != null ? taskItem.getRejectedTools() : null;
            String scopeJson = scopeOverride != null ? scopeOverride
                    : (taskItem != null ? taskItem.getActionScope() : null);

            ActionScopeResolver.Verdict verdict = ActionScopeResolver.resolve(
                    toolName, argumentsJson, policy, excludedTools,
                    rejectedTools, scopeJson, config.getExec());

            if (verdict == ActionScopeResolver.Verdict.ALLOW) {
                String taskId = taskItem != null ? taskItem.getId() : null;
                auditLogService.log(taskId, sessionId, toolName, argumentsJson, null, "ALLOW");
                return null;
            }

            if (verdict == ActionScopeResolver.Verdict.DENY) {
                String taskId = taskItem != null ? taskItem.getId() : null;
                auditLogService.log(taskId, sessionId, toolName, argumentsJson, null, "DENY");
                return String.format("'%s' 도구는 사용이 금지되어 있습니다.", toolName);
            }

            // ASK → RejectionStrategy 위임
            String result = rejection.onDenied(toolName, argumentsJson, taskItem);

            String taskId = taskItem != null ? taskItem.getId() : null;
            String logVerdict = result == null ? "ESCALATED_APPROVED" : "ESCALATED_DENIED";
            auditLogService.log(taskId, sessionId, toolName, argumentsJson, result, logVerdict);

            return result;
        };
    }

    /**
     * LLM에 노출할 도구 목록 (policy=deny + excludedTools 제외).
     */
    public List<String> getVisibleTools(List<String> excludedTools) {
        Set<String> visible = new LinkedHashSet<>(toolRegistry.getActiveToolNames());
        // policy=deny 도구 제거
        visible.removeIf(tool -> "deny".equals(toolPolicyService.getPolicy(tool)));
        // excludedTools 제거
        if (excludedTools != null) {
            visible.removeAll(excludedTools);
        }
        return new ArrayList<>(visible);
    }

    /**
     * LLM에 노출할 도구 목록 (deny 제외 전체).
     */
    public List<String> getVisibleTools() {
        return getVisibleTools(null);
    }
}
