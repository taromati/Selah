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

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryExploreToolHandler {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("memory_explore")
                            .description("엔티티 이름으로 관련 기억 탐색 (지식 그래프 BFS)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "entity_name", Map.of(
                                                    "type", "string",
                                                    "description", "탐색할 엔티티 이름"
                                            ),
                                            "hops", Map.of(
                                                    "type", "integer",
                                                    "description", "탐색 깊이 (기본: 2)"
                                            )
                                    ),
                                    "required", List.of("entity_name")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public MemoryExploreToolHandler(ToolRegistry toolRegistry, MemoryService memoryService,
                                     ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("memory_explore", DEFINITION, this::execute, true, "메모리");
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String entityName = (String) args.get("entity_name");
            int hops = args.containsKey("hops") ? ((Number) args.get("hops")).intValue() : 2;

            if (entityName == null || entityName.isBlank()) {
                return ToolResult.text("엔티티 이름이 비어있습니다.");
            }

            log.info("[MemoryExploreToolHandler] Exploring entity='{}', hops={}", entityName, hops);

            List<MemoryService.ExploreResult> results = memoryService.exploreByName(entityName, hops);

            if (results.isEmpty()) {
                return ToolResult.text("'" + entityName + "'과 관련된 기억을 찾지 못했습니다.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("'").append(entityName).append("' 관련 기억 (").append(results.size()).append("개):\n\n");

            for (int i = 0; i < results.size(); i++) {
                MemoryService.ExploreResult result = results.get(i);
                sb.append(String.format("[%d] (id=%s, %s) %s\n",
                        i + 1,
                        result.chunkId(),
                        result.createdAt().format(DATE_FORMAT),
                        StringUtils.truncateRaw(result.content(), 200)
                ));
            }

            return ToolResult.text(sb.toString().trim());

        } catch (Exception e) {
            log.error("[MemoryExploreToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("메모리 탐색 오류: " + e.getMessage());
        }
    }
}
