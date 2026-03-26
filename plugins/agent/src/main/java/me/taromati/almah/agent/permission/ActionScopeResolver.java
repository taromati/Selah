package me.taromati.almah.agent.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.tool.ExecToolHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 구조적 차단 판정 + exec 보안 검증.
 * 순수 로직 (Spring 의존성 없음).
 *
 * <p>컨텍스트별 policy 판정은 ContextPolicyResolver가 담당.
 * 이 클래스는 excludedTools/rejectedTools 차단과 exec 보안만 수행한다.</p>
 */
@Slf4j
public class ActionScopeResolver {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public enum Verdict { ALLOW, ASK, DENY }

    /**
     * 구조적 차단 판정.
     * excludedTools → rejectedTools → exec 보안.
     * policy 판정은 ContextPolicyResolver가 담당하므로 여기서는 차단 대상 아니면 ALLOW.
     */
    public static Verdict resolve(String toolName, String argumentsJson,
                                   List<String> excludedTools,
                                   String rejectedTools,
                                   AgentConfigProperties.ExecConfig execConfig) {
        // 1. excludedTools
        if (excludedTools != null && excludedTools.contains(toolName)) {
            return Verdict.DENY;
        }

        // 2. rejectedTools (per-task)
        if (rejectedTools != null && !rejectedTools.isBlank()) {
            if (Set.of(rejectedTools.split(",")).contains(toolName)) {
                return Verdict.DENY;
            }
        }

        return Verdict.ALLOW;
    }

    /**
     * exec 보안 검증.
     */
    public static Verdict checkExecSecurity(String argumentsJson,
                                             AgentConfigProperties.ExecConfig execConfig) {
        if (execConfig == null) return Verdict.DENY;
        if ("deny".equals(execConfig.getSecurity())) return Verdict.DENY;

        String command = extractCommand(argumentsJson);
        if (command == null || command.isBlank()) return Verdict.DENY;

        // Blocked patterns
        for (String pattern : execConfig.getBlockedPatterns()) {
            if (command.contains(pattern)) return Verdict.DENY;
        }

        // Chaining
        if (ExecToolHandler.containsChaining(command)) return Verdict.DENY;

        // Full security -> allow all (blocked patterns already checked)
        if ("full".equals(execConfig.getSecurity())) return Verdict.ALLOW;

        // Allowlist security -> check all segments
        List<String> segments = ExecToolHandler.parseCommandSegments(command);
        for (String seg : segments) {
            if (!execConfig.getAllowlist().contains(seg)) return Verdict.DENY;
        }
        return Verdict.ALLOW;
    }

    static String extractCommand(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            Object cmd = args.get("command");
            if (cmd == null) cmd = args.get("commands");
            return cmd instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

}
