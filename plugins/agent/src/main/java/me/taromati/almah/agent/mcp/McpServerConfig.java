package me.taromati.almah.agent.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 서버 연결 설정.
 * agent-data/mcp/{name}.json 으로 영속화.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerConfig(
        String name,
        String transportType,       // "stdio" | "streamable-http" | "sse"
        // STDIO
        String command,
        List<String> args,
        Map<String, String> env,
        // HTTP/SSE
        String url,
        Map<String, String> headers,
        // 공통
        boolean autoConnect,
        int timeoutSeconds,
        int maxRetries,
        // Per-tool policy
        Map<String, String> toolPolicies,  // tool → "allow"|"ask"|"deny"
        String defaultPolicy,              // null이면 빌트인 policyDefault 사용
        String trustLevel,                 // "read_only"|"user_approval"|"always_escalate"|null
        Boolean enabled                    // null/true → 활성, false → 비활성화
) {
    public McpServerConfig {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (transportType == null) transportType = "stdio";
        if (args == null) args = List.of();
        if (env == null) env = Map.of();
        if (headers == null) headers = Map.of();
        if (timeoutSeconds <= 0) timeoutSeconds = 60;
        if (maxRetries <= 0) maxRetries = 5;
        if (toolPolicies == null) toolPolicies = Map.of();
        if (trustLevel != null && !Set.of("read_only", "user_approval", "always_escalate").contains(trustLevel)) {
            throw new IllegalArgumentException("Invalid trustLevel: " + trustLevel);
        }
        if (enabled == null) enabled = true;
    }
}
