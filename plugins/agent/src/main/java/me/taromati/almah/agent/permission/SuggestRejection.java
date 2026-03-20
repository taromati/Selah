package me.taromati.almah.agent.permission;

import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 제안 모드 거부 전략.
 * 도구를 사용할 수 없다는 메시지를 즉시 반환.
 */
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SuggestRejection implements RejectionStrategy {

    @Override
    public String onDenied(String toolName, String argumentsJson, AgentTaskItemEntity taskItem) {
        return String.format("'%s' 도구를 사용할 수 없습니다. 제안 모드에서는 읽기 전용 도구만 허용됩니다.", toolName);
    }
}
