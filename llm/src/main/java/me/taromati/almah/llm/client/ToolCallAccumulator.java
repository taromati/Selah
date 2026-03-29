package me.taromati.almah.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * SSE 스트리밍 응답의 tool_calls delta를 index별로 누적하여 완성된 ToolCall 목록으로 조립하는 유틸리티.
 *
 * <p>사용 패턴:
 * <pre>
 *   accumulator.accumulate(delta.get("tool_calls")); // 청크마다 호출
 *   List&lt;ToolCall&gt; result = accumulator.build();     // 스트림 종료 후 조립
 * </pre>
 * </p>
 */
public class ToolCallAccumulator {

    /** index → 누적 중인 슬롯 */
    private final TreeMap<Integer, Slot> slots = new TreeMap<>();

    /**
     * delta.tool_calls 배열 JsonNode를 받아 index별로 누적한다.
     *
     * @param toolCallsDeltaArray delta.tool_calls 배열 (ArrayNode)
     */
    public void accumulate(JsonNode toolCallsDeltaArray) {
        if (toolCallsDeltaArray == null || !toolCallsDeltaArray.isArray()) {
            return;
        }
        for (JsonNode item : toolCallsDeltaArray) {
            int index = item.path("index").asInt();
            Slot slot = slots.computeIfAbsent(index, i -> new Slot());

            if (item.hasNonNull("id")) {
                slot.id = item.get("id").asText();
            }
            if (item.hasNonNull("type")) {
                slot.type = item.get("type").asText();
            }
            JsonNode function = item.get("function");
            if (function != null) {
                if (function.hasNonNull("name")) {
                    slot.functionName = function.get("name").asText();
                }
                if (function.hasNonNull("arguments")) {
                    slot.argumentsBuilder.append(function.get("arguments").asText());
                }
            }
        }
    }

    /**
     * 누적된 슬롯을 ToolCall 목록으로 변환하여 반환한다.
     * index 오름차순으로 정렬된 새 리스트를 반환한다 (snapshot).
     *
     * @return 완성된 {@code List<ChatCompletionResponse.ToolCall>} (불변 복사본)
     */
    public List<ChatCompletionResponse.ToolCall> build() {
        List<ChatCompletionResponse.ToolCall> result = new ArrayList<>();
        for (Slot slot : slots.values()) {
            ChatCompletionResponse.ToolCall.FunctionCall fc =
                    new ChatCompletionResponse.ToolCall.FunctionCall(
                            slot.functionName,
                            slot.argumentsBuilder.toString()
                    );
            result.add(new ChatCompletionResponse.ToolCall(slot.id, slot.type, fc));
        }
        return List.copyOf(result);
    }

    /** index별 누적 슬롯 */
    private static class Slot {
        String id;
        String type;
        String functionName;
        final StringBuilder argumentsBuilder = new StringBuilder();
    }
}
