package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.mcp.McpClientManager;
import me.taromati.almah.agent.mcp.McpServerConfig;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mcp 관리 도구 — MCP 서버 추가/제거/연결/해제/조회.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class McpToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("mcp")
                            .description("MCP 서버 관리 (추가/제거/연결/해제/목록/도구조회)")
                            .parameters(buildParameters())
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpToolHandler(ToolRegistry toolRegistry, McpClientManager mcpClientManager) {
        this.toolRegistry = toolRegistry;
        this.mcpClientManager = mcpClientManager;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("mcp", DEFINITION, this::execute);
    }

    @SuppressWarnings("unchecked")
    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String action = (String) args.get("action");
            if (action == null) return ToolResult.text("action이 필요합니다");

            return switch (action) {
                case "list" -> handleList();
                case "add" -> handleAdd(args);
                case "remove" -> handleRemove(args);
                case "connect" -> handleConnect(args);
                case "disconnect" -> handleDisconnect(args);
                case "tools" -> handleTools(args);
                default -> ToolResult.text("알 수 없는 action: " + action);
            };
        } catch (Exception e) {
            log.error("[McpToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("MCP 도구 오류: " + e.getMessage());
        }
    }

    private ToolResult handleList() {
        var status = mcpClientManager.getDetailedStatus();
        if (status.isEmpty()) {
            return ToolResult.text("등록된 MCP 서버가 없습니다.");
        }

        StringBuilder sb = new StringBuilder("MCP 서버 목록:\n");
        for (var entry : status.entrySet()) {
            var info = entry.getValue();
            var cfg = mcpClientManager.getConfigs().get(entry.getKey());
            sb.append("- ").append(entry.getKey())
                    .append(" [").append(cfg != null ? cfg.transportType() : "?").append("]")
                    .append(": ").append(info.get("state"));
            int toolCount = (int) info.getOrDefault("toolCount", 0);
            if (toolCount > 0) sb.append(" (").append(toolCount).append("개 도구)");
            if (info.containsKey("error")) sb.append("\n  오류: ").append(info.get("error"));
            if (cfg != null && cfg.autoConnect()) sb.append(" (자동연결)");
            sb.append("\n");
        }
        return ToolResult.text(sb.toString().trim());
    }

    @SuppressWarnings("unchecked")
    private ToolResult handleAdd(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");

        String transport = (String) args.getOrDefault("transport", "stdio");
        String command = (String) args.get("command");
        List<String> argsList = args.containsKey("args") ? (List<String>) args.get("args") : List.of();
        Map<String, String> env = args.containsKey("env") ? toStringMap(args.get("env")) : Map.of();
        String url = (String) args.get("url");
        Map<String, String> headers = args.containsKey("headers") ? toStringMap(args.get("headers")) : Map.of();
        boolean autoConnect = args.containsKey("auto_connect") ? (Boolean) args.get("auto_connect") : true;
        int timeoutSeconds = args.containsKey("timeout_seconds") ? ((Number) args.get("timeout_seconds")).intValue() : 0;
        int maxRetries = args.containsKey("max_retries") ? ((Number) args.get("max_retries")).intValue() : 0;

        @SuppressWarnings("unchecked")
        Map<String, String> toolPolicies = args.containsKey("tool_policies") ? toStringMap(args.get("tool_policies")) : null;
        String defaultPolicy = args.containsKey("default_policy") ? (String) args.get("default_policy") : null;
        Boolean enabled = args.containsKey("enabled") ? (Boolean) args.get("enabled") : null;

        String trustLevel = args.containsKey("trust_level") ? (String) args.get("trust_level") : null;

        McpServerConfig cfg = new McpServerConfig(
                name, transport, command, argsList, env, url, headers, autoConnect, timeoutSeconds, maxRetries,
                toolPolicies, defaultPolicy, trustLevel, enabled);

        return ToolResult.text(mcpClientManager.addServer(cfg));
    }

    private ToolResult handleRemove(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");
        return ToolResult.text(mcpClientManager.removeServer(name));
    }

    private ToolResult handleConnect(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");
        return ToolResult.text(mcpClientManager.reconnect(name));
    }

    private ToolResult handleDisconnect(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");
        return ToolResult.text(mcpClientManager.disconnect(name));
    }

    private ToolResult handleTools(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");

        String connState = mcpClientManager.getConnectionState(name);
        if (!"CONNECTED".equals(connState)) {
            return ToolResult.text("연결되지 않은 서버: " + name + " (상태: " + connState + ")");
        }

        List<String> tools = mcpClientManager.getServerTools(name);
        if (tools.isEmpty()) return ToolResult.text(name + ": 등록된 도구 없음");

        StringBuilder sb = new StringBuilder(name + " 도구 목록 (" + tools.size() + "개):\n");
        for (String tool : tools) {
            sb.append("- ").append(tool).append("\n");
        }
        return ToolResult.text(sb.toString().trim());
    }

    private static Map<String, Object> buildParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "수행할 작업",
                "enum", List.of("list", "add", "remove", "connect", "disconnect", "tools")));
        properties.put("name", Map.of("type", "string", "description", "서버 이름 (add/remove/connect/disconnect/tools)"));
        properties.put("transport", Map.of(
                "type", "string",
                "description", "트랜스포트 유형 (add)",
                "enum", List.of("stdio", "streamable-http", "sse")));
        properties.put("command", Map.of("type", "string", "description", "실행 명령 (stdio)"));
        properties.put("args", Map.of("type", "array", "items", Map.of("type", "string"), "description", "명령 인자 (stdio)"));
        properties.put("env", Map.of("type", "object", "description", "환경 변수 (stdio)"));
        properties.put("url", Map.of("type", "string", "description", "서버 URL (streamable-http/sse)"));
        properties.put("headers", Map.of("type", "object", "description", "HTTP 헤더 (streamable-http/sse)"));
        properties.put("auto_connect", Map.of("type", "boolean", "description", "자동 연결 (기본 true)"));
        properties.put("timeout_seconds", Map.of("type", "integer", "description", "연결/초기화 타임아웃 초 (기본 60)"));
        properties.put("max_retries", Map.of("type", "integer", "description", "최대 재시도 횟수 (기본 5)"));
        properties.put("default_policy", Map.of("type", "string", "description", "서버 기본 정책 (allow/ask/deny)",
                "enum", List.of("allow", "ask", "deny")));
        properties.put("tool_policies", Map.of("type", "object", "description", "도구별 정책 (tool → allow/ask/deny)"));
        properties.put("enabled", Map.of("type", "boolean", "description", "서버 활성화 (기본 true)"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", List.of("action"));
        return params;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toStringMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        }
        return Map.of();
    }
}
