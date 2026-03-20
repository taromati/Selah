package me.taromati.almah.agent.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.task.TaskSource;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.agent.tool.AgentToolContext;
import me.taromati.almah.core.messenger.ChannelRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대화 모드 거부 전략.
 * Discord 승인 버튼 생성 -> 대기 -> 승인 시 scope 확장, 거부 시 LLM에 에러.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ChatRejection {

    private final EscalationService escalationService;
    private final ActionScopeFactory actionScopeFactory;
    private final TaskStoreService taskStoreService;

    /** 세션(채널)별 승인된 도구 캐시. taskItem=null일 때 세션 단위로 승인 기억. */
    private final ConcurrentHashMap<String, Set<String>> sessionApprovedTools = new ConcurrentHashMap<>();

    /**
     * 특정 채널에 대한 RejectionStrategy 생성.
     *
     * @param channel Discord 텍스트 채널 (승인 버튼 전송용)
     * @return RejectionStrategy 인스턴스
     */
    public RejectionStrategy withChannel(ChannelRef channel) {
        return (toolName, argumentsJson, taskItem) -> handleDenied(toolName, argumentsJson, taskItem, channel);
    }

    private String handleDenied(String toolName, String argumentsJson,
                                 AgentTaskItemEntity taskItem, ChannelRef channel) {
        if (channel == null) {
            return "자동 실행 모드에서는 승인을 요청할 수 없습니다";
        }

        // H4: 세션 캐시 확인 (taskItem=null일 때도 이전 승인 기억)
        String channelId = resolveChannelId();
        if (channelId != null && sessionApprovedTools.getOrDefault(channelId, Set.of()).contains(toolName)) {
            return null; // 이전에 승인됨
        }

        String description = describeToolCall(toolName, argumentsJson);
        Boolean approved = escalationService.requestChatApproval(channel, toolName, description, taskItem);

        if (Boolean.TRUE.equals(approved)) {
            // Scope 확장
            if (taskItem != null) {
                String expandedScope = actionScopeFactory.expandScope(taskItem.getActionScope(), toolName, argumentsJson);
                taskStoreService.updateActionScope(taskItem.getId(), expandedScope);
            }
            // H4: 세션 캐시에 승인 기록
            if (channelId != null) {
                sessionApprovedTools.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
            }
            return null; // 승인됨
        }

        if (approved == null) {
            // M9: 타임아웃 -> 할 일로 등록 + scope 설정
            String taskTitle = String.format("[%s] 승인 타임아웃: %s", toolName, description);
            if (taskTitle.length() > 200) taskTitle = taskTitle.substring(0, 200);
            String originalRequest = AgentToolContext.getCurrentUserMessage();
            var task = taskStoreService.create(taskTitle, argumentsJson, originalRequest, TaskSource.CHAT);
            if (actionScopeFactory != null) {
                String scope = actionScopeFactory.createChatScope();
                taskStoreService.updateActionScope(task.getId(), scope);
            }
            return "승인 요청이 타임아웃되었습니다. 할 일로 등록합니다.";
        }

        // 거부 -> rejectedTools에 추가
        if (taskItem != null) {
            taskStoreService.addRejectedTool(taskItem.getId(), toolName);
        }
        return "사용자가 거부했습니다";
    }

    /**
     * 세션 캐시 초기화. 세션 종료 시 호출.
     */
    public void clearSessionCache(String channelId) {
        sessionApprovedTools.remove(channelId);
    }

    private String resolveChannelId() {
        AgentToolContext ctx = AgentToolContext.get();
        return ctx != null ? ctx.channelId() : null;
    }

    private String describeToolCall(String toolName, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson)) {
            return String.format("`%s` 도구를 실행합니다", toolName);
        }
        String truncated = argumentsJson.length() > 100
                ? argumentsJson.substring(0, 100) + "..."
                : argumentsJson;
        return String.format("`%s` 도구를 실행합니다\n> ```json\n> %s\n> ```", toolName, truncated);
    }
}
