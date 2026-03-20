package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * file_read 도구: 파일 내용 읽기
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class FileReadToolHandler {

    private static final int MAX_CONTENT_LENGTH = 8000;

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("file_read")
                            .description("로컬 파일 내용 읽기 (경로를 알고 있을 때)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "path", Map.of(
                                                    "type", "string",
                                                    "description", "읽을 파일 경로"
                                            )
                                    ),
                                    "required", List.of("path")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileReadToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("file_read", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String pathStr = (String) args.get("path");

            if (pathStr == null || pathStr.isBlank()) {
                return ToolResult.text("파일 경로가 비어있습니다.");
            }

            Path path = Path.of(pathStr).toAbsolutePath().normalize();

            // 경로 제한 검증
            if (!isPathAllowed(path)) {
                return ToolResult.text("접근이 허용되지 않은 경로입니다: " + path);
            }

            if (!Files.exists(path)) {
                return ToolResult.text("파일이 존재하지 않습니다: " + path);
            }

            if (Files.isDirectory(path)) {
                return ToolResult.text("디렉토리입니다. bash 도구의 ls 명령을 사용하세요: " + path);
            }

            // 크기 제한
            long sizeKb = Files.size(path) / 1024;
            int maxSizeKb = config.getFile().getMaxFileSizeKb();
            if (sizeKb > maxSizeKb) {
                return ToolResult.text("파일이 너무 큽니다: " + sizeKb + "KB (최대 " + maxSizeKb + "KB)");
            }

            String content = Files.readString(path);
            content = StringUtils.truncateRaw(content, MAX_CONTENT_LENGTH);

            return ToolResult.text(content);

        } catch (Exception e) {
            log.error("[FileReadToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("파일 읽기 오류: " + e.getMessage());
        }
    }

    private boolean isPathAllowed(Path path) {
        List<String> allowedPaths = config.getFile().getAllowedPaths();
        if (allowedPaths == null || allowedPaths.isEmpty()) {
            return true;  // 제한 없음
        }
        for (String allowed : allowedPaths) {
            if (path.startsWith(Path.of(allowed).toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }
}
