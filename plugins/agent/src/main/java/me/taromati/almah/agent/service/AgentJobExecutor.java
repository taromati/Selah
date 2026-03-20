package me.taromati.almah.agent.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentScheduledJobEntity;
import me.taromati.almah.agent.tool.AgentToolContext;
import me.taromati.almah.core.util.PluginMdc;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.agent.permission.PermissionGate;
import me.taromati.almah.llm.tool.ToolCallingService;
import me.taromati.almah.llm.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 예약 잡 실행기.
 * message 타입은 텍스트 그대로 반환, agent-turn은 LLM + Tool Calling 실행.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentJobExecutor {

    public record ExecutionResult(String text, List<String> toolsUsed, int totalTokens) {}

    private final ToolCallingService toolCallingService;
    private final ToolRegistry toolRegistry;
    private final AgentConfigProperties config;
    private final AgentContextBuilder contextBuilder;
    private final PermissionGate permissionGate;
    private final LlmClientResolver clientResolver;
    private final ExecutorService executor;

    public AgentJobExecutor(ToolCallingService toolCallingService,
                            ToolRegistry toolRegistry,
                            AgentConfigProperties config,
                            AgentContextBuilder contextBuilder,
                            PermissionGate permissionGate,
                            LlmClientResolver clientResolver) {
        this.toolCallingService = toolCallingService;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.contextBuilder = contextBuilder;
        this.permissionGate = permissionGate;
        this.clientResolver = clientResolver;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "cron-agent-executor");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 잡 실행.
     *
     * @param job 실행할 잡
     * @return 실행 결과 (텍스트 + 도구 + 토큰)
     */
    public ExecutionResult execute(AgentScheduledJobEntity job) {
        return switch (job.getExecutionType()) {
            case "message" -> new ExecutionResult(job.getPayload(), List.of(), 0);
            case "agent-turn" -> executeAgentTurn(job);
            default -> throw new IllegalArgumentException("Unknown execution type: " + job.getExecutionType());
        };
    }

    private ExecutionResult executeAgentTurn(AgentScheduledJobEntity job) {
        int timeout = job.getMaxDurationMinutes() != null
                ? job.getMaxDurationMinutes() * 60
                : config.getCron().getAgentTurnTimeoutSeconds();

        // Cron은 컨텍스트별 프로바이더 사용, 모델은 null (프로바이더 기본)
        LlmClient client = clientResolver.resolve(config.getLlmProviderName());

        Future<ExecutionResult> future = executor.submit(() -> {
            PluginMdc.set("agent");
            AgentToolContext.set(job.getChannelId(), false, true, client, null);
            try {
                // 시스템 프롬프트 빌드 (AgentContextBuilder와 동일한 구조 공유)
                String systemContent = contextBuilder.buildSystemContent(config.getSystemPrompt(), null, client);

                String cronPrefix = "[예약 작업 '" + job.getName() + "' 실행] "
                        + "아래 지시를 지금 즉시 수행하세요. "
                        + "계획만 세우거나 나중에 하겠다고 하지 말고, 필요한 도구를 바로 호출해서 완료하세요.\n\n";

                List<ChatMessage> messages = List.of(
                        ChatMessage.builder().role("system").content(systemContent).build(),
                        ChatMessage.builder().role("user").content(cronPrefix + job.getPayload()).build()
                );

                // 도구 필터: visible - cron.excludedTools
                List<String> tools = resolveCronTools();

                var effective = config.resolveSessionConfig(client.getCapabilities());

                SamplingParams params = new SamplingParams(
                        effective.maxTokens(), config.getTemperature(),
                        config.getTopP(), config.getMinP(),
                        config.getFrequencyPenalty(), config.getRepetitionPenalty(), null
                );

                int durationMinutes = job.getMaxDurationMinutes() != null
                        ? job.getMaxDurationMinutes() : 5;
                var toolCallingConfig = new ToolCallingService.ToolCallingConfig(
                        effective.contextWindow(),
                        effective.charsPerToken(),
                        durationMinutes);

                // cron 잡은 정책 체크 적용 — ask 도구 즉시 거부, allow 도구 허용
                ToolCallingService.ToolCallingResult result =
                        toolCallingService.chatWithTools(messages, params, tools,
                                permissionGate.createCronFilter(), client, null, toolCallingConfig);

                String response = result.textResponse();
                String text = (response == null || response.isBlank()) ? null : response;

                List<String> toolsUsed = extractToolNames(result.intermediateMessages());

                return new ExecutionResult(text, toolsUsed, result.totalTokens());
            } finally {
                AgentToolContext.clear();
                PluginMdc.clear();
            }
        });

        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("agent-turn 타임아웃 (" + timeout + "초)");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("agent-turn 실행 오류: " + cause.getMessage(), cause);
        }
    }

    private List<String> extractToolNames(List<ChatMessage> messages) {
        if (messages == null) return List.of();
        return messages.stream()
                .filter(m -> m.getToolCalls() != null)
                .flatMap(m -> m.getToolCalls().stream())
                .map(ChatCompletionResponse.ToolCall::getFunction)
                .map(ChatCompletionResponse.ToolCall.FunctionCall::getName)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> resolveCronTools() {
        return permissionGate.getVisibleTools(config.getCron().getExcludedTools());
    }

}
