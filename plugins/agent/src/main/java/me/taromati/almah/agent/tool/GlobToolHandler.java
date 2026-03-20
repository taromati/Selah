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

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * glob 도구: glob 패턴으로 파일 검색
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class GlobToolHandler {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "build", ".gradle", "__pycache__", ".idea", "target"
    );

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("glob")
                            .description("glob 패턴 파일 검색")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "pattern", Map.of("type", "string", "description", "패턴"),
                                            "path", Map.of("type", "string", "description", "시작 디렉토리")
                                    ),
                                    "required", List.of("pattern")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GlobToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("glob", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String pattern = (String) args.get("pattern");
            String pathStr = (String) args.get("path");

            if (pattern == null || pattern.isBlank()) {
                return ToolResult.text("패턴이 비어있습니다.");
            }

            Path basePath = (pathStr != null && !pathStr.isBlank())
                    ? Path.of(pathStr).toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir"));

            if (!isPathAllowed(basePath)) {
                return ToolResult.text("접근이 허용되지 않은 경로입니다: " + basePath);
            }

            if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
                return ToolResult.text("디렉토리가 존재하지 않습니다: " + basePath);
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            int maxResults = config.getFile().getMaxSearchResults();
            int maxDepth = config.getFile().getMaxSearchDepth();
            List<String> results = new ArrayList<>();

            Files.walkFileTree(basePath, Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString()) && !dir.equals(basePath)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return results.size() >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relativePath = basePath.relativize(file);
                    if (matcher.matches(relativePath)) {
                        results.add(relativePath.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) {
                return ToolResult.text("매칭되는 파일이 없습니다.");
            }

            var sb = new StringBuilder();
            for (String result : results) {
                sb.append(result).append('\n');
            }
            if (results.size() >= maxResults) {
                sb.append("\n... 결과가 %d개로 제한되었습니다.".formatted(maxResults));
            }
            sb.append("\n총 %d개 파일".formatted(results.size()));

            return ToolResult.text(sb.toString());

        } catch (Exception e) {
            log.error("[GlobToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("glob 검색 오류: " + e.getMessage());
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
