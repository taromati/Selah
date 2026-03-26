package me.taromati.almah.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 단일 MCP 서버의 연결 생명주기를 캡슐화.
 * State: IDLE → CONNECTING → CONNECTED → (프로세스 종료) → RECONNECTING → CONNECTED
 *                                          └→ (max retries 초과) → FAILED
 */
@Slf4j
class McpConnection {

    enum State { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    record AuthNotification(Instant timestamp, String message, String url, String code) {}

    private static final int MAX_STDERR_LINES = 100;
    private static final int MAX_NOTIFICATIONS = 10;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(?:code|코드)[:\\s]+([A-Z0-9]{4,}[-\\s]?[A-Z0-9]{4,})", Pattern.CASE_INSENSITIVE);

    private volatile McpServerConfig config;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final McpClientManager manager;

    private volatile State state = State.IDLE;
    private volatile String lastError;
    private volatile int retryCount = 0;
    private McpSyncClient client;
    private Map<String, String> registeredToolMap = Map.of();
    private final Deque<String> stderrBuffer = new ConcurrentLinkedDeque<>();
    private final Deque<AuthNotification> authNotifications = new ConcurrentLinkedDeque<>();

    McpConnection(McpServerConfig config, ToolRegistry toolRegistry, ObjectMapper objectMapper, McpClientManager manager) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.manager = manager;
    }

    // ── Public API ──

    /**
     * 동기 연결 (blocking). autoConnect 배경 스레드에서 호출.
     */
    synchronized String connectSync() {
        if (state == State.CONNECTED) disconnect();
        state = State.CONNECTING;
        lastError = null;
        retryCount = 0;
        doConnectWork();
        return state == State.CONNECTED
                ? "연결 성공 (" + registeredToolMap.size() + "개 도구)"
                : "연결 실패: " + lastError;
    }

    /**
     * 비동기 연결. 즉시 반환하고 백그라운드에서 연결.
     */
    String connectAsync() {
        synchronized (this) {
            if (state == State.CONNECTING) return "이미 연결 진행 중";
            if (state == State.CONNECTED) disconnect();
            state = State.CONNECTING;
            lastError = null;
            retryCount = 0;
        }
        CompletableFuture.runAsync(this::doConnectWork);
        return "연결 시작됨";
    }

    synchronized String disconnect() {
        unregisterTools();
        closeClient();
        state = State.IDLE;
        lastError = null;
        retryCount = 0;
        return "연결 해제";
    }

    ToolResult callTool(String originalToolName, String argsJson) {
        if (state != State.CONNECTED || client == null) {
            return ToolResult.text("MCP 서버 '" + config.name() + "'에 연결되어 있지 않습니다 (상태: " + state + ")");
        }

        try {
            Map<String, Object> arguments = argsJson != null && !argsJson.isBlank()
                    ? objectMapper.readValue(argsJson, new TypeReference<>() {})
                    : Map.of();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(originalToolName, arguments));

            return McpToolConverter.convertResult(result);

        } catch (Exception e) {
            log.error("[MCP] Tool call failed: {}/{} - {}", config.name(), originalToolName, e.getMessage());
            return ToolResult.text("MCP 도구 호출 오류: " + e.getMessage());
        }
    }

    // ── Getters ──

    State getState() { return state; }
    String getLastError() { return lastError; }
    McpServerConfig getConfig() { return config; }
    List<String> getToolNames() { return List.copyOf(registeredToolMap.keySet()); }
    List<String> getLocalToolNames() { return List.copyOf(registeredToolMap.values()); }
    int getRetryCount() { return retryCount; }
    List<String> getStderrLog() { return List.copyOf(stderrBuffer); }
    List<AuthNotification> getAuthNotifications() { return List.copyOf(authNotifications); }
    void clearAuthNotifications() { authNotifications.clear(); }

    String getLocalToolName(String namespacedName) {
        return registeredToolMap.get(namespacedName);
    }

    /**
     * 핫 config 교체 — 재연결 불필요한 필드만 변경 시 사용.
     */
    void updateHotConfig(McpServerConfig newConfig) {
        this.config = newConfig;
    }

    // ── Private: 연결 작업 ──

    /**
     * 실제 연결 작업. connectSync()에서는 동기 호출, connectAsync()/scheduleReconnect()에서는 비동기 호출.
     */
    private void doConnectWork() {
        synchronized (this) {
            if (state == State.IDLE) return; // 수동 disconnect 후엔 무시
        }

        McpSyncClient newClient = null;
        try {
            McpClientTransport transport = createTransport();
            newClient = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .initializationTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .jsonSchemaValidator(noOpValidator())
                    .toolsChangeConsumer(this::onToolsChanged)
                    .build();

            newClient.initialize();

            var toolsResult = newClient.listTools();

            Map<String, String> newMap;
            synchronized (this) {
                if (state == State.IDLE) {
                    // disconnect()/removeServer()가 연결 중에 호출됨 — 정리 후 중단
                    try { newClient.closeGracefully(); } catch (Exception ignored) {}
                    return;
                }
                newMap = registerTools(toolsResult.tools(), newClient);
                this.client = newClient;
                this.registeredToolMap = newMap;
                state = State.CONNECTED;
                retryCount = 0;
            }
            log.info("[MCP] '{}': 연결 성공 ({}개 도구)", config.name(), newMap.size());
            manager.notifyListeners(config.name(), true);

            // STDIO 프로세스 감시 시작
            if (transport instanceof StdioClientTransport stdioTransport) {
                CompletableFuture.runAsync(() -> {
                    try {
                        stdioTransport.awaitForExit();
                        if (state == State.CONNECTED) {
                            log.warn("[MCP] '{}': 프로세스 종료 감지, 재연결 시도", config.name());
                            synchronized (McpConnection.this) {
                                unregisterTools();
                                closeClient();
                            }
                            manager.notifyListeners(config.name(), false);
                            scheduleReconnect();
                        }
                    } catch (Exception e) {
                        log.debug("[MCP] '{}': awaitForExit 예외: {}", config.name(), e.getMessage());
                    }
                });
            }

        } catch (Throwable t) {
            if (newClient != null) {
                try { newClient.closeGracefully(); } catch (Exception ignored) {}
            }
            boolean wasReconnecting;
            synchronized (this) {
                wasReconnecting = retryCount > 0;
                unregisterTools();
                state = State.FAILED;
                lastError = extractErrorDetail(t);
            }
            log.warn("[MCP] '{}': 연결 실패 — {}", config.name(), lastError);
            manager.notifyListeners(config.name(), false);

            // 재연결 중이었으면 다시 시도
            if (wasReconnecting && retryCount < config.maxRetries()) {
                scheduleReconnect();
            }

            if (t instanceof Error err) throw err;
        }
    }

    // ── Private: 재연결 ──

    private void scheduleReconnect() {
        synchronized (this) {
            if (state == State.IDLE) return;
            if (retryCount >= config.maxRetries()) {
                state = State.FAILED;
                lastError = "최대 재시도 횟수 초과 (" + config.maxRetries() + "회)";
                log.warn("[MCP] '{}': {}", config.name(), lastError);
                return;
            }
            state = State.RECONNECTING;
            retryCount++;
        }
        long delay = Math.min(1000L * (1L << (retryCount - 1)), 30_000L);
        log.info("[MCP] '{}': {}ms 후 재연결 시도 ({}/{})", config.name(), delay, retryCount, config.maxRetries());
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(this::doConnectWork);
    }

    // ── Private: Transport ──

    private McpClientTransport createTransport() {
        return switch (config.transportType()) {
            case "stdio" -> {
                String cmd = config.command();
                if (cmd == null || cmd.isBlank()) {
                    throw new IllegalStateException("STDIO 트랜스포트에 command가 필요합니다");
                }
                List<String> argsList = new ArrayList<>(config.args());
                if (cmd.contains(" ") && argsList.isEmpty()) {
                    String[] parts = cmd.split("\\s+");
                    cmd = parts[0];
                    argsList.addAll(Arrays.asList(parts).subList(1, parts.length));
                }
                ServerParameters params = ServerParameters.builder(cmd)
                        .args(argsList)
                        .env(config.env())
                        .build();
                StdioClientTransport stdioTransport = new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper));
                stdioTransport.setStdErrorHandler(this::handleStderr);
                yield stdioTransport;
            }
            case "streamable-http" -> {
                if (config.url() == null || config.url().isBlank()) {
                    throw new IllegalArgumentException("Streamable HTTP 트랜스포트에 URL이 필요합니다");
                }
                yield HttpClientStreamableHttpTransport.builder(config.url())
                        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                        .build();
            }
            case "sse" -> {
                if (config.url() == null || config.url().isBlank()) {
                    throw new IllegalArgumentException("SSE 트랜스포트에 URL이 필요합니다");
                }
                yield HttpClientSseClientTransport.builder(config.url())
                        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                        .build();
            }
            default -> throw new IllegalArgumentException("지원하지 않는 트랜스포트: " + config.transportType());
        };
    }

    private void handleStderr(String line) {
        stderrBuffer.addLast(line);
        while (stderrBuffer.size() > MAX_STDERR_LINES) stderrBuffer.pollFirst();
        log.debug("[MCP-stderr] {}: {}", config.name(), line);

        // 인증 URL/코드 감지
        Matcher urlMatcher = URL_PATTERN.matcher(line);
        if (urlMatcher.find()) {
            String url = urlMatcher.group();
            Matcher codeMatcher = CODE_PATTERN.matcher(line);
            String code = codeMatcher.find() ? codeMatcher.group(1) : null;
            authNotifications.addLast(new AuthNotification(Instant.now(), line, url, code));
            while (authNotifications.size() > MAX_NOTIFICATIONS) authNotifications.pollFirst();
            log.info("[MCP-auth] '{}': 인증 필요 — {} (code: {})", config.name(), url, code);
        }
    }

    // ── Private: Tool 등록/해제 ──

    private Map<String, String> registerTools(List<McpSchema.Tool> tools, McpSyncClient targetClient) {
        Map<String, String> nameMap = new LinkedHashMap<>();
        for (McpSchema.Tool tool : tools) {
            String nsName = McpToolConverter.namespacedName(config.name(), tool.name());
            var definition = McpToolConverter.convert(nsName, tool);

            String originalName = tool.name();
            toolRegistry.register(nsName, definition, args -> callTool(originalName, args), true, "MCP/" + config.name());
            nameMap.put(nsName, originalName);
        }
        return nameMap;
    }

    private void unregisterTools() {
        if (!registeredToolMap.isEmpty()) {
            toolRegistry.unregisterAll(List.copyOf(registeredToolMap.keySet()));
            registeredToolMap = Map.of();
        }
    }

    private void onToolsChanged(List<McpSchema.Tool> newTools) {
        log.info("[MCP] Tools changed for '{}', re-registering {} tools", config.name(), newTools.size());
        synchronized (this) {
            if (client == null) return;
            unregisterTools();
            registeredToolMap = registerTools(newTools, client);
        }
    }

    // ── Private: 유틸 ──

    private void closeClient() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("[MCP] Error closing client '{}': {}", config.name(), e.getMessage());
            }
            client = null;
        }
    }

    /**
     * No-op JSON Schema 검증기.
     * 외부 MCP 서버의 출력 스키마 불일치(예: 숫자 vs 문자열)로 인한 validation error 방지.
     * ServiceLoader 우회 효과도 유지 (fat JAR classloader 문제 해결).
     */
    private JsonSchemaValidator noOpValidator() {
        return (schema, data) -> {
            try {
                String json = objectMapper.writeValueAsString(data);
                return JsonSchemaValidator.ValidationResponse.asValid(json);
            } catch (Exception e) {
                return JsonSchemaValidator.ValidationResponse.asValid(
                        data != null ? data.toString() : "");
            }
        };
    }

    private String extractErrorDetail(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();

        if (root instanceof java.util.concurrent.TimeoutException) {
            StringBuilder sb = new StringBuilder();
            sb.append("초기화 타임아웃 (").append(config.timeoutSeconds()).append("초). ");
            if ("stdio".equals(config.transportType())) {
                sb.append("원인 후보: (1) 프로세스가 MCP 프로토콜 응답을 보내지 않음, ");
                sb.append("(2) 의존성 설치 중 (최초 실행 시), ");
                sb.append("(3) 인증이 필요한 서버 (env에 토큰 설정 필요)");
            } else {
                sb.append("서버 URL이 접근 가능한지, MCP 프로토콜을 지원하는지 확인하세요. URL: ").append(config.url());
            }
            return sb.toString();
        }

        return root.getMessage() != null ? root.getMessage() : e.getClass().getSimpleName();
    }
}
