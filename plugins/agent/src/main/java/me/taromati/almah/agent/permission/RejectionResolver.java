package me.taromati.almah.agent.permission;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.tool.AgentToolContext.ExecutionContext;
import me.taromati.almah.core.messenger.ChannelRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ExecutionContext → RejectionStrategy 매핑.
 * PermissionGate가 컨텍스트별 RejectionStrategy를 직접 선택하지 않고,
 * ExecutionContext로부터 자동 결정한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class RejectionResolver {

    private final ChatRejection chatRejection;
    private final RoutineRejection routineRejection;

    private static final RejectionStrategy IMMEDIATE_REJECT =
            (toolName, args, task) ->
                    String.format("'%s' 도구를 사용할 수 없습니다.", toolName);

    /**
     * 실행 컨텍스트에 따라 적절한 RejectionStrategy를 반환한다.
     *
     * CHAT → Discord 버튼 에스컬레이션 (channel 필요)
     * ROUTINE → WAITING_APPROVAL 전환 (taskItem 필요)
     * SUGGEST, CRON, SUBAGENT → 즉시 거부 (에스컬레이션 경로 없음)
     */
    public RejectionStrategy resolve(ExecutionContext context, ChannelRef channel,
                                      AgentTaskItemEntity taskItem) {
        return switch (context) {
            case CHAT -> chatRejection.withChannel(channel);
            case ROUTINE -> routineRejection;
            case SUGGEST, CRON, SUBAGENT -> IMMEDIATE_REJECT;
        };
    }
}
