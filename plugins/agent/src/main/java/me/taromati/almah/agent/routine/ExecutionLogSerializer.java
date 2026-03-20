package me.taromati.almah.agent.routine;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ExecutionLogEntry 리스트의 JSON 직렬화/역직렬화 + Markdown 렌더링.
 */
@Slf4j
@Component
public class ExecutionLogSerializer {

    private static final int MAX_RESULT_LENGTH = 200;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;

    public ExecutionLogSerializer() {
        this.objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        this.objectMapper.registerModule(module);
    }

    public String serialize(List<ExecutionLogEntry> entries) {
        try {
            return objectMapper.writeValueAsString(entries);
        } catch (Exception e) {
            log.warn("[ExecutionLogSerializer] 직렬화 실패: {}", e.getMessage());
            return "[]";
        }
    }

    public List<ExecutionLogEntry> deserialize(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("[ExecutionLogSerializer] 역직렬화 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * buildTaskPrompt()에서 사용할 Markdown 생성.
     */
    public String toMarkdown(List<ExecutionLogEntry> entries) {
        if (entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (var entry : entries) {
            sb.append("### 실행 ").append(entry.cycle());
            if (entry.timestamp() != null) {
                sb.append(" (").append(FORMATTER.format(entry.timestamp())).append(")");
            }
            sb.append("\n");

            for (var tc : entry.toolCalls()) {
                sb.append("- ").append(tc.tool()).append(" → ").append(tc.resultSummary()).append("\n");
            }

            if (entry.llmConclusion() != null) {
                sb.append("- 결론: ").append(entry.llmConclusion()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 기존 JSON에 엔트리를 append하고, maxEntries를 초과하면 가장 오래된 것을 제거.
     */
    public String append(String existingJson, ExecutionLogEntry entry, int maxEntries) {
        List<ExecutionLogEntry> entries = new ArrayList<>(deserialize(existingJson));
        entries.add(entry);
        while (entries.size() > maxEntries) {
            entries.remove(0);
        }
        return serialize(entries);
    }

    /**
     * ToolCallingService의 intermediateMessages에서 ExecutionLogEntry 생성.
     */
    public ExecutionLogEntry fromToolCallingResult(List<ChatMessage> intermediateMessages, int cycle) {
        List<ExecutionLogEntry.ToolCallSummary> toolCalls = new ArrayList<>();
        String lastAssistantContent = null;

        // tool_calls를 가진 assistant 메시지와 그에 대한 tool response를 매칭
        for (int i = 0; i < intermediateMessages.size(); i++) {
            ChatMessage msg = intermediateMessages.get(i);

            if ("assistant".equals(msg.getRole())) {
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    // tool_calls가 있는 assistant 메시지 — 다음 tool responses와 매칭
                    for (var tc : msg.getToolCalls()) {
                        String toolName = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
                        String toolCallId = tc.getId();

                        // 대응하는 tool response 찾기
                        String resultSummary = findToolResponse(intermediateMessages, i + 1, toolCallId);
                        toolCalls.add(new ExecutionLogEntry.ToolCallSummary(toolName, resultSummary));
                    }
                } else {
                    // 텍스트만 있는 assistant 메시지 — conclusion 후보
                    String content = msg.getContentAsString();
                    if (content != null && !content.isBlank()) {
                        lastAssistantContent = content;
                    }
                }
            }
        }

        return new ExecutionLogEntry(cycle, Instant.now(), toolCalls, lastAssistantContent, null);
    }

    private String findToolResponse(List<ChatMessage> messages, int startIdx, String toolCallId) {
        for (int i = startIdx; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("tool".equals(msg.getRole())) {
                if (toolCallId == null || toolCallId.equals(msg.getToolCallId())) {
                    return truncate(msg.getContentAsString());
                }
            }
            // assistant 메시지를 만나면 이 tool_call의 응답 범위를 벗어남
            if ("assistant".equals(msg.getRole())) break;
        }
        return "";
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > MAX_RESULT_LENGTH ? text.substring(0, MAX_RESULT_LENGTH) : text;
    }
}
