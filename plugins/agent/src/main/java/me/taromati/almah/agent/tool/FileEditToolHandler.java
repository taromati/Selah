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
 * edit 도구: 파일 부분 편집 (old_string → new_string)
 *
 * <p>file_write는 전체 덮어쓰기만 가능하지만,
 * edit는 특정 문자열만 정확히 치환할 수 있습니다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class FileEditToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("edit")
                            .description("파일 부분 편집 (old→new 치환)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "path", Map.of(
                                                    "type", "string",
                                                    "description", "편집할 파일 경로"
                                            ),
                                            "old_string", Map.of(
                                                    "type", "string",
                                                    "description", "찾을 문자열 (정확히 일치해야 함)"
                                            ),
                                            "new_string", Map.of(
                                                    "type", "string",
                                                    "description", "치환할 문자열"
                                            )
                                    ),
                                    "required", List.of("path", "old_string", "new_string")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public FileEditToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry,
                                ObjectMapper objectMapper) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("edit", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String pathStr = (String) args.get("path");
            String oldString = (String) args.get("old_string");
            String newString = (String) args.get("new_string");

            if (pathStr == null || pathStr.isBlank()) {
                return ToolResult.text("파일 경로가 비어있습니다.");
            }
            if (oldString == null || oldString.isEmpty()) {
                return ToolResult.text("old_string이 비어있습니다.");
            }
            if (newString == null) {
                newString = "";
            }

            Path path = Path.of(pathStr).toAbsolutePath().normalize();

            // 경로 제한 검증
            if (!isPathAllowed(path)) {
                return ToolResult.text("접근이 허용되지 않은 경로입니다: " + path);
            }

            // 자율 사고 시 agent-data/ 외부 편집 차단
            AgentToolContext ctx = AgentToolContext.get();
            if (ctx != null && ctx.selfGeneration()) {
                Path agentData = Path.of(config.getDataDir()).toAbsolutePath().normalize();
                if (!path.startsWith(agentData)) {
                    return ToolResult.text("[자율 사고] agent-data/ 외부 파일은 수정할 수 없습니다: " + path);
                }
            }

            if (!Files.exists(path)) {
                return ToolResult.text("파일이 존재하지 않습니다: " + path);
            }

            if (Files.isDirectory(path)) {
                return ToolResult.text("디렉토리는 편집할 수 없습니다: " + path);
            }

            // 크기 제한
            long sizeKb = Files.size(path) / 1024;
            int maxSizeKb = config.getFile().getMaxFileSizeKb();
            if (sizeKb > maxSizeKb) {
                return ToolResult.text("파일이 너무 큽니다: " + sizeKb + "KB (최대 " + maxSizeKb + "KB)");
            }

            String content = Files.readString(path);

            // old_string 존재 여부 확인
            int index = content.indexOf(oldString);
            if (index == -1) {
                return ToolResult.text("old_string을 파일에서 찾을 수 없습니다.");
            }

            // 첫 번째 발생만 치환
            String newContent = content.substring(0, index) + newString + content.substring(index + oldString.length());
            Files.writeString(path, newContent);

            int occurrences = countOccurrences(content, oldString);
            log.info("[FileEditToolHandler] Edited {}: replaced {} bytes → {} bytes", path, oldString.length(), newString.length());

            String result = "파일을 편집했습니다: " + path;
            if (occurrences > 1) {
                result += " (첫 번째 발생만 치환, 총 " + occurrences + "개 발견)";
            }
            return ToolResult.text(result);

        } catch (Exception e) {
            log.error("[FileEditToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("파일 편집 오류: " + e.getMessage());
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

    private static int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }
}
