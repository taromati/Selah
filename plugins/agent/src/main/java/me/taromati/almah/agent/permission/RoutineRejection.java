package me.taromati.almah.agent.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 루틴 모드 거부 전략.
 * EscalationService 호출 -> WAITING_APPROVAL + 다른 할 일로 이동.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class RoutineRejection implements RejectionStrategy {

    private final EscalationService escalationService;

    @Override
    public String onDenied(String toolName, String argumentsJson, AgentTaskItemEntity taskItem) {
        if (taskItem == null) {
            return String.format("'%s' 도구를 사용할 수 없습니다 (할 일 없음)", toolName);
        }

        // 에스컬레이션: WAITING_APPROVAL 전환 + Discord 알림
        escalationService.escalateForRoutine(taskItem, toolName, argumentsJson);

        return String.format("'%s' 도구 실행에 승인이 필요합니다. 작업이 승인 대기 상태로 전환되었습니다. 다른 작업으로 전환하세요.", toolName);
    }
}
