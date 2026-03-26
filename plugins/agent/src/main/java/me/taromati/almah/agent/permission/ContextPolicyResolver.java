package me.taromati.almah.agent.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.config.AgentConfigProperties.ToolsConfig.ContextPolicy;
import me.taromati.almah.agent.tool.AgentToolContext.ExecutionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 컨텍스트별 도구 허용/거부 판정.
 * 글로벌 deny → MCP trustLevel → 컨텍스트 allow/escalate → default.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ContextPolicyResolver {

    private final AgentConfigProperties config;
    private final ToolGroupResolver toolGroupResolver;
    private final ToolPolicyService toolPolicyService;

    /**
     * 컨텍스트 + 도구명 → ALLOW / ASK / DENY 판정.
     */
    public ActionScopeResolver.Verdict resolve(ExecutionContext context, String toolName) {
        var toolsConfig = config.getTools();

        // 1. global-deny
        if (isGlobalDeny(toolName)) {
            return ActionScopeResolver.Verdict.DENY;
        }

        // 2. MCP trustLevel
        String trustLevel = toolPolicyService.getMcpTrustLevel(toolName);
        if ("always_escalate".equals(trustLevel)) {
            return ActionScopeResolver.Verdict.ASK;
        }
        if ("read_only".equals(trustLevel)) {
            // read_only MCP: per-tool deny가 아니면 자동 ALLOW
            String mcpPolicy = toolPolicyService.getMcpToolPolicy(toolName);
            if (!"deny".equals(mcpPolicy)) {
                return ActionScopeResolver.Verdict.ALLOW;
            }
            return ActionScopeResolver.Verdict.DENY;
        }

        // 3. 컨텍스트 정책 조회
        Map<String, List<String>> groups = toolsConfig.getGroups();
        ContextPolicy contextPolicy = toolsConfig.getContexts().get(contextKey(context));

        if (contextPolicy == null) {
            // 미정의 컨텍스트 → deny 폴백
            return ActionScopeResolver.Verdict.DENY;
        }

        // 4. allow 매칭
        Set<String> resolvedAllow = toolGroupResolver.resolve(contextPolicy.getAllow(), groups);
        if (toolGroupResolver.matches(toolName, resolvedAllow)) {
            return ActionScopeResolver.Verdict.ALLOW;
        }

        // 5. escalate 매칭 (Routine만)
        if (context == ExecutionContext.ROUTINE && contextPolicy.getEscalate() != null) {
            Set<String> resolvedEscalate = toolGroupResolver.resolve(contextPolicy.getEscalate(), groups);
            if (toolGroupResolver.matches(toolName, resolvedEscalate)) {
                return ActionScopeResolver.Verdict.ASK;
            }
        }

        // 6. 컨텍스트 default
        return "ask".equals(contextPolicy.getDefaultPolicy())
                ? ActionScopeResolver.Verdict.ASK
                : ActionScopeResolver.Verdict.DENY;
    }

    /**
     * global-deny 여부 확인.
     */
    public boolean isGlobalDeny(String toolName) {
        List<String> globalDeny = config.getTools().getGlobalDeny();
        return globalDeny != null && globalDeny.contains(toolName);
    }

    /**
     * 컨텍스트에서 허용된 도구 집합 (LLM 도구 목록 생성용).
     * allow + escalate + read_only MCP 도구를 합산한다.
     */
    public Set<String> getAllowedTools(ExecutionContext context) {
        var toolsConfig = config.getTools();
        ContextPolicy contextPolicy = toolsConfig.getContexts().get(contextKey(context));

        Set<String> allowed = new LinkedHashSet<>();
        if (contextPolicy != null) {
            allowed.addAll(toolGroupResolver.resolve(contextPolicy.getAllow(), toolsConfig.getGroups()));
            if (contextPolicy.getEscalate() != null) {
                allowed.addAll(toolGroupResolver.resolve(contextPolicy.getEscalate(), toolsConfig.getGroups()));
            }
        }

        // Chat default=ask: "*" (모든 도구)
        if (contextPolicy != null && "ask".equals(contextPolicy.getDefaultPolicy())) {
            allowed.add("*");
        }

        return allowed;
    }

    private String contextKey(ExecutionContext context) {
        return context.name().toLowerCase();
    }
}
