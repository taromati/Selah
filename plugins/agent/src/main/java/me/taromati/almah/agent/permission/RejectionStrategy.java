package me.taromati.almah.agent.permission;

import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;

/**
 * 도구 거부 시 처리 전략.
 * 실행 컨텍스트(대화/루틴/제안)에 따라 구현이 달라진다.
 */
@FunctionalInterface
public interface RejectionStrategy {

    /**
     * 도구가 ActionScope 밖일 때 호출.
     *
     * @param toolName      거부된 도구 이름
     * @param argumentsJson 도구 인자 JSON
     * @param taskItem      현재 할 일 (nullable — 대화 중 할 일 없이 실행될 수 있음)
     * @return null이면 승인됨 (scope 확장 후), 문자열이면 거부 사유
     */
    String onDenied(String toolName, String argumentsJson, AgentTaskItemEntity taskItem);
}
