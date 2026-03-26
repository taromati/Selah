package me.taromati.almah.memory.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.MemoryService;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryStoreToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("memory_store")
                            .description("기억 저장")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "content", Map.of(
                                                    "type", "string",
                                                    "description", "내용"
                                            ),
                                            "label", Map.of(
                                                    "type", "string",
                                                    "description", "라벨 (선택)"
                                            )
                                    ),
                                    "required", List.of("content")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public MemoryStoreToolHandler(ToolRegistry toolRegistry, MemoryService memoryService,
                                   ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("memory_store", DEFINITION, this::execute, true, "메모리");
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String content = (String) args.get("content");
            String label = (String) args.get("label");

            if (content == null || content.isBlank()) {
                return ToolResult.text("저장할 내용이 비어있습니다.");
            }

            String fullContent = content;
            if (label != null && !label.isBlank()) {
                fullContent = "[" + label + "] " + content;
            }

            Map<String, String> metadata = new HashMap<>();
            metadata.put("source", "memory_store_tool");
            if (label != null && !label.isBlank()) {
                metadata.put("label", label);
            }
            memoryService.ingest(fullContent, metadata);

            log.info("[MemoryStoreToolHandler] Stored memory: content={}", StringUtils.truncate(fullContent, 80));

            return ToolResult.text("기억을 저장했습니다.");

        } catch (Exception e) {
            log.error("[MemoryStoreToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("기억 저장 오류: " + e.getMessage());
        }
    }
}
