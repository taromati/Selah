package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.core.util.LoginShellProcess;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.LlmRateLimiter;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Gemini CLI 기반 검색/조사 서브에이전트 도구.
 * <p>
 * Gemini CLI의 내장 도구(google_web_search, codebase_investigator 등)를 활용하여
 * 복잡한 검색/조사를 1회 호출로 처리하고 요약만 반환합니다.
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class GeminiToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("gemini")
                            .description("Gemini에게 조사를 위임합니다. "
                                    + "Google 검색 + 로컬 파일/코드 분석을 자동 조합하여 요약을 반환합니다. "
                                    + "여러 소스를 종합하거나 조사 후 정리가 필요할 때 사용하세요. "
                                    + "단순 사실 확인은 web_search가 빠릅니다.")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "prompt", Map.of("type", "string",
                                                    "description", "Gemini에게 전달할 조사/검색 요청")
                                    ),
                                    "required", List.of("prompt")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final LoginShellProcess loginShellProcess;
    private final ObjectMapper objectMapper;
    private final LlmRateLimiter rateLimiter = new LlmRateLimiter(60);

    public GeminiToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry,
                             LoginShellProcess loginShellProcess, ObjectMapper objectMapper) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.loginShellProcess = loginShellProcess;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        var geminiConfig = config.getGemini();
        if (geminiConfig != null && Boolean.TRUE.equals(geminiConfig.getEnabled())) {
            toolRegistry.register("gemini", DEFINITION, this::execute, true, "AI");
            log.info("[GeminiTool] 등록 완료 (cli={}, model={})",
                    geminiConfig.getCliPath(), geminiConfig.getModel());
        }
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String prompt = (String) args.get("prompt");

            if (prompt == null || prompt.isBlank()) {
                return ToolResult.text("prompt가 필요합니다.");
            }

            var geminiConfig = config.getGemini();
            String cliPath = geminiConfig.getCliPath();
            String model = geminiConfig.getModel();
            int timeout = geminiConfig.getTimeoutSeconds() != null ? geminiConfig.getTimeoutSeconds() : 120;

            log.info("[GeminiTool] 실행: model={}, prompt={}", model, StringUtils.truncate(prompt, 80));

            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.text("gemini 도구 오류: 인터럽트됨");
            }

            ProcessBuilder pb = loginShellProcess.create(cliPath, "--model", model, "-p", prompt);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // stdout + stderr 동시 읽기 (데드락 방지)
            String stdout;
            String stderr;
            try (
                    var outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    var errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
            ) {
                stdout = outReader.lines().collect(Collectors.joining("\n"));
                stderr = errReader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.text("Gemini CLI 타임아웃 (" + timeout + "초)");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[GeminiTool] 비정상 종료 (exit={}): {}", exitCode, stderr);
                return ToolResult.text("Gemini CLI 오류 (exit=" + exitCode + "): " + stderr);
            }

            if (stdout.isBlank()) {
                return ToolResult.text("(Gemini 빈 응답)");
            }

            log.info("[GeminiTool] 응답 {}자", stdout.length());
            return ToolResult.text(stdout.trim());

        } catch (Exception e) {
            log.error("[GeminiTool] 실행 오류: {}", e.getMessage());
            return ToolResult.text("gemini 도구 오류: " + e.getMessage());
        }
    }

}
