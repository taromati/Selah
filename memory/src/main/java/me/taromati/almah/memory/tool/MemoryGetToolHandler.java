package me.taromati.almah.memory.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryGetToolHandler {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("memory_get")
                            .description("ID로 기억 조회")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "id", Map.of(
                                                    "type", "string",
                                                    "description", "청크 ID"
                                            )
                                    ),
                                    "required", List.of("id")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final MemoryChunkRepository chunkRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryGetToolHandler(ToolRegistry toolRegistry, MemoryChunkRepository chunkRepository) {
        this.toolRegistry = toolRegistry;
        this.chunkRepository = chunkRepository;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("memory_get", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String id = (String) args.get("id");

            if (id == null || id.isBlank()) {
                return ToolResult.text("ID가 비어있습니다.");
            }

            Optional<MemoryChunkEntity> entity = chunkRepository.findById(id);
            if (entity.isEmpty()) {
                return ToolResult.text("ID에 해당하는 기억을 찾을 수 없습니다: " + id);
            }

            MemoryChunkEntity chunk = entity.get();
            StringBuilder sb = new StringBuilder();
            sb.append("ID: ").append(chunk.getId()).append("\n");
            sb.append("소스ID: ").append(chunk.getSourceId()).append("\n");
            sb.append("청크: ").append(chunk.getChunkIndex() + 1).append("/").append(chunk.getTotalChunks()).append("\n");
            sb.append("생성시각: ").append(chunk.getCreatedAt().format(DATE_FORMAT)).append("\n");
            if (chunk.getMetadata() != null) {
                sb.append("메타데이터: ").append(chunk.getMetadata()).append("\n");
            }
            if (Boolean.TRUE.equals(chunk.getConsolidated())) {
                sb.append("통합 요약: 예\n");
            }
            sb.append("내용:\n").append(chunk.getContent());

            return ToolResult.text(sb.toString());

        } catch (Exception e) {
            log.error("[MemoryGetToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("기억 조회 오류: " + e.getMessage());
        }
    }
}
