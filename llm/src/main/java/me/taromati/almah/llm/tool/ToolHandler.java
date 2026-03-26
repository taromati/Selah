package me.taromati.almah.llm.tool;

/**
 * Tool 핸들러 함수 인터페이스
 * 각 도구 구현체가 ToolRegistry에 등록할 때 사용
 */
@FunctionalInterface
public interface ToolHandler {
    ToolResult execute(String argumentsJson);
}
