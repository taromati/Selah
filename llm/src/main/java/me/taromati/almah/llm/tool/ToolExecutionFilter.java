package me.taromati.almah.llm.tool;

/**
 * 도구 실행 전 권한 확인 필터.
 * Agent에서 승인 기반 도구 정책을 구현할 때 사용.
 */
@FunctionalInterface
public interface ToolExecutionFilter {

    /**
     * 도구 실행 전 권한 확인.
     * 블로킹 가능 (Discord 승인 대기 등).
     *
     * @param toolName      도구 이름
     * @param argumentsJson 도구 인자 (JSON)
     * @return null이면 허용, 문자열이면 차단 사유
     */
    String checkPermission(String toolName, String argumentsJson);
}
