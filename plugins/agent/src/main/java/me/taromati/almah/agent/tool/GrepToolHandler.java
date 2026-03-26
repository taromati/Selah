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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * grep 도구: 파일 내용에서 정규식 패턴 검색
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class GrepToolHandler {

    private static final int MAX_LINE_LENGTH = 200;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "build", ".gradle", "__pycache__", ".idea", "target"
    );

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("grep")
                            .description("정규식으로 파일 내용 검색")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "pattern", Map.of("type", "string", "description", "정규식"),
                                            "path", Map.of("type", "string", "description", "경로"),
                                            "include", Map.of("type", "string", "description", "파일 필터 (*.java)")
                                    ),
                                    "required", List.of("pattern")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public GrepToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry,
                            ObjectMapper objectMapper) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("grep", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String patternStr = (String) args.get("pattern");
            String pathStr = (String) args.get("path");
            String include = (String) args.get("include");

            if (patternStr == null || patternStr.isBlank()) {
                return ToolResult.text("검색 패턴이 비어있습니다.");
            }

            Pattern regex;
            try {
                regex = Pattern.compile(patternStr);
            } catch (PatternSyntaxException e) {
                return ToolResult.text("잘못된 정규식 패턴: " + e.getMessage());
            }

            Path targetPath = (pathStr != null && !pathStr.isBlank())
                    ? Path.of(pathStr).toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir"));

            if (!isPathAllowed(targetPath)) {
                return ToolResult.text("접근이 허용되지 않은 경로입니다: " + targetPath);
            }

            if (!Files.exists(targetPath)) {
                return ToolResult.text("경로가 존재하지 않습니다: " + targetPath);
            }

            int maxResults = config.getFile().getMaxSearchResults();
            List<String> results = new ArrayList<>();

            if (Files.isRegularFile(targetPath)) {
                searchFile(targetPath, targetPath.getParent(), regex, results, maxResults);
            } else {
                PathMatcher includeMatcher = (include != null && !include.isBlank())
                        ? FileSystems.getDefault().getPathMatcher("glob:" + include)
                        : null;
                int maxDepth = config.getFile().getMaxSearchDepth();
                long maxSizeBytes = config.getFile().getMaxFileSizeKb() * 1024L;

                Files.walkFileTree(targetPath, Set.of(), maxDepth, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (SKIP_DIRS.contains(dir.getFileName().toString()) && !dir.equals(targetPath)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return results.size() >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (results.size() >= maxResults) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (attrs.size() > maxSizeBytes) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                            return FileVisitResult.CONTINUE;
                        }
                        searchFile(file, targetPath, regex, results, maxResults);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            if (results.isEmpty()) {
                return ToolResult.text("매칭되는 결과가 없습니다.");
            }

            var sb = new StringBuilder();
            for (String result : results) {
                sb.append(result).append('\n');
            }
            if (results.size() >= maxResults) {
                sb.append("\n... 결과가 %d개로 제한되었습니다.".formatted(maxResults));
            }
            sb.append("\n총 %d개 매치".formatted(results.size()));

            return ToolResult.text(sb.toString());

        } catch (Exception e) {
            log.error("[GrepToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("grep 검색 오류: " + e.getMessage());
        }
    }

    private void searchFile(Path file, Path basePath, Pattern regex, List<String> results, int maxResults) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int lineNumber = 0;
            String relativePath = basePath.relativize(file).toString();
            while ((line = reader.readLine()) != null && results.size() < maxResults) {
                lineNumber++;
                Matcher matcher = regex.matcher(line);
                if (matcher.find()) {
                    String truncatedLine = StringUtils.truncateRaw(line.strip(), MAX_LINE_LENGTH);
                    results.add("%s:%d: %s".formatted(relativePath, lineNumber, truncatedLine));
                }
            }
        } catch (Exception e) {
            // 바이너리 파일 등 읽기 실패 시 조용히 스킵
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
