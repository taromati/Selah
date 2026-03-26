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

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemorySearchToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("memory_search")
                            .description("과거 대화 검색")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "query", Map.of(
                                                    "type", "string",
                                                    "description", "검색 쿼리"
                                            )
                                    ),
                                    "required", List.of("query")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public MemorySearchToolHandler(ToolRegistry toolRegistry, MemoryService memoryService,
                                    ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("memory_search", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String query = (String) args.get("query");

            if (query == null || query.isBlank()) {
                return ToolResult.text("검색 쿼리가 비어있습니다.");
            }

            log.info("[MemorySearchToolHandler] Searching: {}", StringUtils.truncate(query, 80));

            List<MemoryService.SearchResult> results = memoryService.search(query);

            if (results.isEmpty()) {
                return ToolResult.text("관련 기록을 찾지 못했습니다.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("검색 결과 (").append(results.size()).append("개):\n\n");

            for (int i = 0; i < results.size(); i++) {
                MemoryService.SearchResult result = results.get(i);
                sb.append(String.format("[%d] (id=%s) %s\n",
                        i + 1,
                        result.chunkId(),
                        StringUtils.truncateRaw(result.content(), 200)
                ));
            }

            return ToolResult.text(sb.toString().trim());

        } catch (Exception e) {
            log.error("[MemorySearchToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("메모리 검색 오류: " + e.getMessage());
        }
    }
}
