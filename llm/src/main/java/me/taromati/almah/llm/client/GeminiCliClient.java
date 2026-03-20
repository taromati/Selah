package me.taromati.almah.llm.client;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.util.LoginShellProcess;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.util.LlmResponseSanitizer;
import me.taromati.almah.llm.config.LlmConfigProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Gemini CLI 기반 LLM 프로바이더.
 * OpenAiClient를 상속하지 않고 LlmClient를 직접 구현합니다 (HTTP가 아닌 CLI 기반).
 */
@Slf4j
public class GeminiCliClient implements LlmClient {

    private final String providerName;
    private final String cliPath;
    private final String model;
    private final int timeoutSeconds;
    private final LlmRateLimiter rateLimiter;
    private final LlmConfigProperties.ProviderConfig providerConfig;
    private final LoginShellProcess loginShellProcess;

    public GeminiCliClient(String providerName, String cliPath, String model, int timeoutSeconds,
                           LlmRateLimiter rateLimiter,
                           LlmConfigProperties.ProviderConfig providerConfig,
                           LoginShellProcess loginShellProcess) {
        this.providerName = providerName;
        this.cliPath = cliPath;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.rateLimiter = rateLimiter;
        this.providerConfig = providerConfig;
        this.loginShellProcess = loginShellProcess;
    }

    @Override
    public ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params,
                                                  ChatCompletionRequest.ToolConfig toolConfig, String model) {
        warnIfInsideTransaction();

        String serialized = serializeMessages(messages, toolConfig);

        // Rate Limiter
        if (rateLimiter != null) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenAiClientException("Rate limiter interrupted", e);
            }
        }

        String stdout = executeCli(serialized);
        ChatCompletionResponse resp = buildResponse(stdout.trim());
        LlmResponseSanitizer.sanitize(resp);
        return resp;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public String getDefaultModel() {
        return model;
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        if (providerConfig == null) return ProviderCapabilities.empty();
        return new ProviderCapabilities(
                providerConfig.getContextWindow(),
                providerConfig.getMaxTokens(),
                providerConfig.getCharsPerToken(),
                providerConfig.getRecentKeep()
        );
    }

    @Override
    public String getSystemPromptHints() {
        return """
                - 도구를 사용하려면 [funcName(param1="값")] 형식으로 호출하세요.
                - 응답에 도구 호출과 텍스트를 섞어도 됩니다.
                - 시간/조건이 명시된 작업은 조건을 먼저 확인하고 충족될 때만 실행하세요.
                - 조건이 충족된 작업은 바로 실행하세요. 사용자에게 "~할까요?" 확인을 구하지 마세요.""";
    }

    // ─── 내부 구현 ───

    /**
     * 메시지와 도구 정의를 텍스트 프롬프트로 직렬화
     */
    String serializeMessages(List<ChatMessage> messages, ChatCompletionRequest.ToolConfig toolConfig) {
        var sb = new StringBuilder();

        // 도구 정의 삽입
        if (toolConfig != null && toolConfig.getTools() != null && !toolConfig.getTools().isEmpty()) {
            sb.append("사용 가능한 도구:\n");
            for (var tool : toolConfig.getTools()) {
                var func = tool.getFunction();
                if (func == null) continue;
                sb.append("- ").append(func.getName());
                serializeToolParameters(sb, func.getParameters());
                sb.append(": ").append(func.getDescription() != null ? func.getDescription() : "").append('\n');
            }
            sb.append('\n');
            sb.append("도구를 사용하려면 [funcName(param1=\"값\")] 형식으로 호출하세요.\n");
            sb.append("응답에 도구 호출과 텍스트를 섞어도 됩니다.\n\n");
        }

        // 메시지 직렬화
        for (var msg : messages) {
            String role = msg.getRole();
            String label = switch (role) {
                case "system" -> "[System]";
                case "user" -> "[User]";
                case "assistant" -> "[Assistant]";
                case "tool" -> "[Tool Result]";
                default -> "[" + role + "]";
            };
            sb.append(label).append('\n');
            String content = msg.getContentAsString();
            if (content != null) {
                sb.append(content).append('\n');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private void serializeToolParameters(StringBuilder sb, Map<String, Object> parameters) {
        if (parameters == null) return;
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) parameters.get("properties");
        if (properties == null || properties.isEmpty()) return;

        sb.append('(');
        boolean first = true;
        for (var entry : properties.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> propDef) {
                Object type = propDef.get("type");
                if (type != null) {
                    sb.append(": ").append(type);
                }
            }
        }
        sb.append(')');
    }

    /**
     * Gemini CLI 실행 (GeminiToolHandler.execute() 패턴 재사용)
     */
    private String executeCli(String prompt) {
        try {
            ProcessBuilder pb = loginShellProcess.create(cliPath, "--model", model, "-p", prompt);
            pb.redirectErrorStream(false);

            log.debug("[GeminiCliClient] CLI 실행: model={}, prompt 길이={}자", model, prompt.length());

            Process process = pb.start();

            String stdout;
            String stderr;
            try (
                    var outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    var errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
            ) {
                stdout = outReader.lines().collect(Collectors.joining("\n"));
                stderr = errReader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new OpenAiClientException("Gemini CLI 타임아웃 (" + timeoutSeconds + "초)");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[GeminiCliClient] 비정상 종료 (exit={}): {}", exitCode, stderr);
                throw new OpenAiClientException("Gemini CLI 오류 (exit=" + exitCode + "): " + stderr);
            }

            if (stdout.isBlank()) {
                log.warn("[GeminiCliClient] 빈 응답");
                return "(빈 응답)";
            }

            log.debug("[GeminiCliClient] 응답 {}자", stdout.length());
            return stdout;

        } catch (OpenAiClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiClientException("Gemini CLI 인터럽트", e);
        } catch (Exception e) {
            throw new OpenAiClientException("Gemini CLI 실행 오류: " + e.getMessage(), e);
        }
    }

    private ChatCompletionResponse buildResponse(String content) {
        var message = new ChatCompletionResponse.ResponseMessage("assistant", content, null);
        var choice = new ChatCompletionResponse.Choice(0, message, "stop");
        var response = new ChatCompletionResponse();
        response.setModel(model);
        response.setChoices(List.of(choice));
        return response;
    }

    private void warnIfInsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.error("[GeminiCliClient] TX 내부 LLM 호출 감지!");
        }
    }
}
