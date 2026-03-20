package me.taromati.almah.memory.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.StructuredQueryService;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryQueryToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("memory_query")
                            .description("메모리 데이터에 대한 구조화 쿼리 실행 (집계, 통계, 비교, 랭킹)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "query", Map.of(
                                                    "type", "string",
                                                    "description", "자연어 쿼리 (예: '가장 많이 언급된 사람은?', '지난 주 대화 횟수')"
                                            )
                                    ),
                                    "required", List.of("query")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final StructuredQueryService structuredQueryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryQueryToolHandler(ToolRegistry toolRegistry, StructuredQueryService structuredQueryService) {
        this.toolRegistry = toolRegistry;
        this.structuredQueryService = structuredQueryService;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("memory_query", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String query = (String) args.get("query");

            if (query == null || query.isBlank()) {
                return ToolResult.text("쿼리가 비어있습니다.");
            }

            log.info("[MemoryQueryToolHandler] Structured query: {}", query);

            StructuredQueryService.QueryResult result = structuredQueryService.query(query);

            StringBuilder sb = new StringBuilder();
            sb.append("실행된 SQL:\n```sql\n").append(result.executedSql()).append("\n```\n\n");

            if (result.rows().isEmpty()) {
                sb.append("결과가 없습니다.");
            } else {
                sb.append("결과 (").append(result.rows().size()).append("행):\n");
                Map<String, Object> firstRow = result.rows().getFirst();
                sb.append("| ").append(String.join(" | ", firstRow.keySet())).append(" |\n");
                sb.append("| ").append(firstRow.keySet().stream().map(k -> "---").collect(Collectors.joining(" | "))).append(" |\n");
                int limit = Math.min(result.rows().size(), 20);
                for (int i = 0; i < limit; i++) {
                    Map<String, Object> row = result.rows().get(i);
                    sb.append("| ");
                    for (String key : firstRow.keySet()) {
                        Object val = row.get(key);
                        sb.append(val != null ? truncate(val.toString(), 100) : "NULL").append(" | ");
                    }
                    sb.append("\n");
                }
                if (result.rows().size() > 20) {
                    sb.append("... 외 ").append(result.rows().size() - 20).append("행 생략\n");
                }
            }

            return ToolResult.text(sb.toString().trim());

        } catch (SecurityException e) {
            log.warn("[MemoryQueryToolHandler] Security violation: {}", e.getMessage());
            return ToolResult.text("보안 위반: " + e.getMessage());
        } catch (Exception e) {
            log.error("[MemoryQueryToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("구조화 쿼리 오류: " + e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
