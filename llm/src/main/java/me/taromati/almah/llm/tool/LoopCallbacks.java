package me.taromati.almah.llm.tool;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 도구 호출 루프 내 외부 콜백 묶음.
 * onIntermediateText: 비스트리밍 모드에서 중간 텍스트 전달.
 * incomingMessagePoll: 매 라운드 시작 시 사용자 메시지 주입.
 */
public record LoopCallbacks(
    Consumer<String> onIntermediateText,
    Supplier<String> incomingMessagePoll
) {
    public static LoopCallbacks empty() {
        return new LoopCallbacks(null, null);
    }
}
