package me.taromati.almah.agent.tool;

import java.util.LinkedHashSet;
import java.util.Set;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.permission.PermissionGate;
import me.taromati.almah.agent.service.AgentContextBuilder;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.tool.ToolCallingService;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * spawn_subagent 도구: 동기 블로킹 서브에이전트 실행.
 *
 * <p>메인 에이전트가 복잡한 작업을 서브에이전트에게 위임합니다.
 * 서브에이전트는 별도 LLM 세션으로 실행되며, 완료 시 결과를 반환합니다.</p>
 *
 * <h2>제약</h2>
 * <ul>
 *   <li>깊이 1: 서브에이전트는 spawn_subagent를 호출할 수 없음</li>
 *   <li>세션/Discord 도구 사용 불가 (기본 deny)</li>
 *   <li>Semaphore로 동시 실행 제한</li>
 *   <li>타임아웃 적용</li>
 *   <li>DB 영속화 불필요 (임시 실행)</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SubagentToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("spawn_subagent")
                            .description("서브에이전트에 작업 위임 (독립 LLM 세션)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "task", Map.of("type", "string", "description", "작업 설명"),
                                            "tools", Map.of(
                                                    "type", "array",
                                                    "items", Map.of("type", "string"),
                                                    "description", "사용할 도구 목록 (생략 시 core 도구만, 서브에이전트가 tool_search로 추가 로드 가능)"
                                            ),
                                            "provider", Map.of("type", "string",
                                                    "description", "LLM 프로바이더 (생략=부모 상속, 예: openai, vllm)"),
                                            "model", Map.of("type", "string",
                                                    "description", "LLM 모델 (생략=부모 상속, 예: gpt-4.1, gemma-3-27b)")
                                    ),
                                    "required", List.of("task")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolCallingService toolCallingService;
    private final PermissionGate permissionGate;
    private final ToolRegistry toolRegistry;
    private final LlmClientResolver clientResolver;
    private final AgentContextBuilder contextBuilder;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final ExecutorService executor;

    public SubagentToolHandler(AgentConfigProperties config,
                               ToolCallingService toolCallingService,
                               PermissionGate permissionGate,
                               ToolRegistry toolRegistry,
                               LlmClientResolver clientResolver,
                               AgentContextBuilder contextBuilder,
                               ObjectMapper objectMapper) {
        this.config = config;
        this.toolCallingService = toolCallingService;
        this.permissionGate = permissionGate;
        this.toolRegistry = toolRegistry;
        this.clientResolver = clientResolver;
        this.contextBuilder = contextBuilder;
        this.semaphore = new Semaphore(config.getSubagent().getMaxConcurrent());
        this.executor = Executors.newCachedThreadPool();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("spawn_subagent", DEFINITION, this::execute, true, "시스템");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private ToolResult execute(String argumentsJson) {
        // 재귀 방지: 서브에이전트 내부에서 호출 시 차단
        AgentToolContext ctx = AgentToolContext.get();
        if (ctx != null && ctx.isSubagent()) {
            return ToolResult.text("서브에이전트에서는 spawn_subagent를 호출할 수 없습니다.");
        }

        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String task = (String) args.get("task");
            List<String> requestedTools = args.get("tools") != null
                    ? (List<String>) args.get("tools")
                    : null;
            String providerArg = (String) args.get("provider");
            String modelArg = (String) args.get("model");

            if (task == null || task.isBlank()) {
                return ToolResult.text("task가 필요합니다.");
            }

            // LLM client + model 해석: 부모 상속 or 지정
            LlmClient client;
            String model;
            if (providerArg != null && !providerArg.isBlank()) {
                try {
                    client = clientResolver.resolve(providerArg);
                } catch (IllegalArgumentException e) {
                    return ToolResult.text(e.getMessage());
                }
                model = modelArg;
            } else {
                // 부모 client/model 상속
                client = ctx != null && ctx.client() != null ? ctx.client() : clientResolver.resolve(config.getLlmProviderName());
                model = modelArg != null ? modelArg : (ctx != null ? ctx.model() : null);
            }

            // 도구 목록 구성: visible - excludedTools
            List<String> availableTools = resolveSubagentTools(requestedTools);
            log.info("[SubagentToolHandler] Spawning subagent: task='{}', tools={}, provider={}, model={}",
                    task, availableTools, client.getProviderName(), model);

            // Semaphore 획득 (즉시 실패 → 대기열 회피)
            if (!semaphore.tryAcquire()) {
                return ToolResult.text("서브에이전트 동시 실행 한도(" + config.getSubagent().getMaxConcurrent() + ")를 초과했습니다. 잠시 후 다시 시도하세요.");
            }

            try {
                // 타임아웃 적용하여 별도 스레드에서 실행
                String channelId = ctx != null ? ctx.channelId() : null;
                LlmClient finalClient = client;
                String finalModel = model;
                Future<ToolCallingService.ToolCallingResult> future = executor.submit(
                        () -> executeSubagent(task, availableTools, channelId, finalClient, finalModel)
                );

                int timeout = config.getSubagent().getTimeoutSeconds();
                ToolCallingService.ToolCallingResult result = future.get(timeout, TimeUnit.SECONDS);

                String response = result.textResponse();
                if (response == null || response.isBlank()) {
                    response = "(서브에이전트가 빈 응답을 반환했습니다)";
                }

                log.info("[SubagentToolHandler] Subagent completed: {} chars, {} tokens",
                        response.length(), result.totalTokens());
                return ToolResult.text(response);

            } catch (TimeoutException e) {
                log.warn("[SubagentToolHandler] Subagent timed out after {}s", config.getSubagent().getTimeoutSeconds());
                return ToolResult.text("서브에이전트 실행 시간 초과 (" + config.getSubagent().getTimeoutSeconds() + "초)");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("[SubagentToolHandler] Subagent execution error: {}", cause.getMessage());
                return ToolResult.text("서브에이전트 실행 오류: " + cause.getMessage());
            } finally {
                semaphore.release();
            }

        } catch (Exception e) {
            log.error("[SubagentToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("spawn_subagent 오류: " + e.getMessage());
        }
    }

    private ToolCallingService.ToolCallingResult executeSubagent(String task, List<String> tools,
                                                                  String channelId, LlmClient client, String model) {
        // 서브에이전트 컨텍스트 설정
        AgentToolContext.set(channelId, true, false, client, model);
        try {
            List<ChatMessage> messages = List.of(
                    ChatMessage.builder()
                            .role("system")
                            .content(contextBuilder.buildSubagentSystemContent(task, client))
                            .build(),
                    ChatMessage.builder()
                            .role("user")
                            .content(task)
                            .build()
            );

            var effective = config.resolveSessionConfig(client.getCapabilities());

            SamplingParams params = new SamplingParams(
                    effective.maxTokens(), config.getTemperature(),
                    config.getTopP(), config.getMinP(),
                    config.getFrequencyPenalty(), config.getRepetitionPenalty(), null
            );

            var toolCallingConfig = new ToolCallingService.ToolCallingConfig(
                    effective.contextWindow(),
                    effective.charsPerToken(),
                    5);

            // 서브에이전트는 정책 체크 적용 — ask 도구 즉시 거부, allow 도구 허용
            return toolCallingService.chatWithTools(messages, params, tools,
                    permissionGate.createSubagentFilter(config.getSubagent().getExcludedTools()),
                    client, model, toolCallingConfig);
        } finally {
            AgentToolContext.clear();
        }
    }

    /**
     * 서브에이전트가 사용할 도구 목록 결정.
     * requestedTools 지정 시: core + 지정된 도구 합집합 (discoverable 포함).
     * 미지정 시: core 도구만 (서브에이전트도 tool_search 사용 가능).
     */
    List<String> resolveSubagentTools(List<String> requestedTools) {
        List<String> excludedTools = config.getSubagent().getExcludedTools();

        if (requestedTools != null && !requestedTools.isEmpty()) {
            Set<String> tools = new LinkedHashSet<>(permissionGate.getCoreTools(excludedTools));
            List<String> filtered = permissionGate.filterTools(requestedTools, excludedTools);
            tools.addAll(filtered);
            return new ArrayList<>(tools);
        }

        return permissionGate.getCoreTools(excludedTools);
    }
}
