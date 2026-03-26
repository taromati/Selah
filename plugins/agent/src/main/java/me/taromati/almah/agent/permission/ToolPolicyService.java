package me.taromati.almah.agent.permission;

import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.mcp.McpClientManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Per-tool policy 통합 조회 서비스.
 * config.yml + MCP JSON을 통합하여 도구별 최종 정책을 결정한다.
 *
 * <p>조회 우선순위:</p>
 * <ol>
 *   <li>MCP 도구: 서버별 toolPolicies → 서버 defaultPolicy</li>
 *   <li>빌트인 policy 맵</li>
 *   <li>빌트인 policyDefault</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ToolPolicyService {

    private final AgentConfigProperties config;

    @Autowired(required = false)
    private McpClientManager mcpClientManager;

    public ToolPolicyService(AgentConfigProperties config) {
        this.config = config;
    }

    /**
     * 도구의 최종 정책 반환.
     *
     * @param toolName 도구 이름
     * @return "allow" | "ask" | "deny"
     */
    public String getPolicy(String toolName) {
        // 1. MCP 도구 — 역방향 매핑으로 정확한 서버/도구 조회
        if (toolName.startsWith("mcp_") && mcpClientManager != null) {
            String mcpPolicy = mcpClientManager.getToolPolicyByNamespacedName(toolName);

            // 1-1. trustLevel 기반 정책 강제
            String trustLevel = mcpClientManager.getTrustLevelByNamespacedName(toolName);
            if ("always_escalate".equals(trustLevel)) {
                // deny는 always_escalate보다 상위 — 유지 (TL-2)
                if ("deny".equals(mcpPolicy)) return "deny";
                // 그 외(allow/ask/null) → 강제 "ask" (TL-1)
                return "ask";
            }

            // 1-2. mcpPolicy가 있으면 반환 (per-tool 명시)
            if (mcpPolicy != null) return mcpPolicy;

            // 1-3. trustLevel 기반 기본 정책 (per-tool 미설정 시)
            if ("read_only".equals(trustLevel)) return "allow";  // TL-3
            // user_approval 또는 null → 기존 흐름 유지 (TL-4, TL-5)
        }

        // 2. 빌트인 policy 맵
        String policy = config.getTools().getPolicy().get(toolName);
        if (policy != null) return policy;

        // 3. policyDefault
        return config.getTools().getPolicyDefault();
    }

    /**
     * MCP 도구의 trustLevel 반환.
     * MCP 도구가 아니면 null.
     */
    public String getMcpTrustLevel(String toolName) {
        if (!toolName.startsWith("mcp_") || mcpClientManager == null) return null;
        return mcpClientManager.getTrustLevelByNamespacedName(toolName);
    }

    /**
     * MCP 도구의 per-tool policy 반환 (trustLevel과 별도).
     * MCP 도구가 아니거나 per-tool 미설정이면 null.
     */
    public String getMcpToolPolicy(String toolName) {
        if (!toolName.startsWith("mcp_") || mcpClientManager == null) return null;
        return mcpClientManager.getToolPolicyByNamespacedName(toolName);
    }
}
