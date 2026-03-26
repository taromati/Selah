package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
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
 * file_write 도구: 파일에 내용 쓰기
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class FileWriteToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("file_write")
                            .description("파일 쓰기 (덮어쓰기/생성)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "path", Map.of(
                                                    "type", "string",
                                                    "description", "쓸 파일 경로"
                                            ),
                                            "content", Map.of(
                                                    "type", "string",
                                                    "description", "파일에 쓸 내용"
                                            )
                                    ),
                                    "required", List.of("path", "content")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public FileWriteToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry,
                                 ObjectMapper objectMapper) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("file_write", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String pathStr = (String) args.get("path");
            String content = (String) args.get("content");

            if (pathStr == null || pathStr.isBlank()) {
                return ToolResult.text("파일 경로가 비어있습니다.");
            }
            if (content == null) {
                content = "";
            }

            Path path = Path.of(pathStr).toAbsolutePath().normalize();

            // 경로 제한 검증
            if (!isPathAllowed(path)) {
                return ToolResult.text("접근이 허용되지 않은 경로입니다: " + path);
            }

            // 자율 사고 시 agent-data/ 외부 쓰기 차단
            AgentToolContext ctx = AgentToolContext.get();
            if (ctx != null && ctx.selfGeneration()) {
                Path agentData = Path.of(config.getDataDir()).toAbsolutePath().normalize();
                if (!path.startsWith(agentData)) {
                    return ToolResult.text("[자율 사고] agent-data/ 외부 파일은 수정할 수 없습니다: " + path);
                }
            }

            // 부모 디렉토리 생성
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content);
            log.info("[FileWriteToolHandler] Written {} bytes to {}", content.length(), path);

            return ToolResult.text("파일을 저장했습니다: " + path + " (" + content.length() + " bytes)");

        } catch (Exception e) {
            log.error("[FileWriteToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("파일 쓰기 오류: " + e.getMessage());
        }
    }

    private boolean isPathAllowed(Path path) {
        List<String> allowedPaths = config.getFile().getAllowedPaths();
        if (allowedPaths == null || allowedPaths.isEmpty()) {
            return true;
        }
        for (String allowed : allowedPaths) {
            if (path.startsWith(Path.of(allowed).toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }
}
