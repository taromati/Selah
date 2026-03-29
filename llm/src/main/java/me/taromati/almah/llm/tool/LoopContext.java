package me.taromati.almah.llm.tool;

import java.util.function.Supplier;

/**
 * ToolCallingService 도구 호출 루프의 컨텍스트 파라미터 묶음.
 * 기존 12인자 chatWithToolsCore의 콜백/설정 인자를 캡슐화한다.
 */
public record LoopContext(
    ToolCallingService.ToolCallingConfig config,
    Supplier<Boolean> cancelCheck,
    String toolChoice,
    LoopCallbacks callbacks
) {
    public LoopContext {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (cancelCheck == null) cancelCheck = () -> false;
    }
}
