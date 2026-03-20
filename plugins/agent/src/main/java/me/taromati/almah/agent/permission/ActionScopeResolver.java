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
 * ActionScope JSON 대조 -> 허용/에스컬레이션/거부 판정.
 * 경로 패턴, 명령 목록, 차단 패턴 매칭. 순수 로직 (Spring 의존성 없음).
 *
 * <p>Per-tool policy 기반 3-state 판정이 primary.
 * ActionScope JSON은 per-task 승인 추적용으로 사용.</p>
 */
@Slf4j
public class ActionScopeResolver {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public enum Verdict { ALLOW, ASK, DENY }

    /**
     * Per-tool policy 기반 판정 (primary).
     *
     * <p>우선순위: excludedTools → rejectedTools → policy → exec 보안</p>
     *
     * @param toolName       도구 이름
     * @param argumentsJson  도구 인자 JSON
     * @param policy         "allow"|"ask"|"deny"
     * @param excludedTools  모드별 제외 도구 목록
     * @param rejectedTools  per-task 거부된 도구 (콤마 구분)
     * @param actionScopeJson per-task ActionScope JSON (ask 시 기존 승인 확인용)
     * @param execConfig     exec 보안 설정
     * @return ALLOW, ASK, 또는 DENY
     */
    public static Verdict resolve(String toolName, String argumentsJson,
                                   String policy,
                                   List<String> excludedTools,
                                   String rejectedTools,
                                   String actionScopeJson,
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

        // 3. per-tool policy
        if ("deny".equals(policy)) return Verdict.DENY;

        if ("ask".equals(policy)) {
            // ActionScope에 이미 승인된 도구인지 확인
            if (isInScope(actionScopeJson, toolName)) {
                // exec는 추가 보안 검증
                if ("exec".equals(toolName)) {
                    return toVerdict(checkExecSecurity(argumentsJson, execConfig));
                }
                return Verdict.ALLOW;
            }
            return Verdict.ASK;
        }

        // "allow"
        if ("exec".equals(toolName)) {
            return toVerdict(checkExecSecurity(argumentsJson, execConfig));
        }
        return Verdict.ALLOW;
    }

    /**
     * ActionScope JSON에서 도구 존재 여부 확인.
     */
    private static boolean isInScope(String actionScopeJson, String toolName) {
        Map<String, Object> scope = parseScope(actionScopeJson);
        if (scope.isEmpty()) return false;
        Object toolScope = scope.get(toolName);
        return toolScope != null;
    }

    /**
     * 레거시 2-state Verdict → 3-state 변환.
     * checkExecSecurity 결과(ALLOW/DENY)를 그대로 사용.
     */
    private static Verdict toVerdict(ExecVerdict execVerdict) {
        return execVerdict == ExecVerdict.ALLOW ? Verdict.ALLOW : Verdict.DENY;
    }

    private enum ExecVerdict { ALLOW, DENY }

    /**
     * exec 보안 검증 (전역 규칙).
     */
    static ExecVerdict checkExecSecurity(String argumentsJson,
                                          AgentConfigProperties.ExecConfig execConfig) {
        if (execConfig == null) return ExecVerdict.DENY;
        if ("deny".equals(execConfig.getSecurity())) return ExecVerdict.DENY;

        String command = extractCommand(argumentsJson);
        if (command == null || command.isBlank()) return ExecVerdict.DENY;

        // Blocked patterns
        for (String pattern : execConfig.getBlockedPatterns()) {
            if (command.contains(pattern)) return ExecVerdict.DENY;
        }

        // Chaining
        if (ExecToolHandler.containsChaining(command)) return ExecVerdict.DENY;

        // Full security -> allow all (blocked patterns already checked)
        if ("full".equals(execConfig.getSecurity())) return ExecVerdict.ALLOW;

        // Allowlist security -> check all segments
        List<String> segments = ExecToolHandler.parseCommandSegments(command);
        for (String seg : segments) {
            if (!execConfig.getAllowlist().contains(seg)) return ExecVerdict.DENY;
        }
        return ExecVerdict.ALLOW;
    }

    @SuppressWarnings("unchecked")
    static Verdict checkPathConditions(String argumentsJson, Map<?, ?> conditions) {
        Object pathsObj = conditions.get("paths");
        if (pathsObj == null) return Verdict.ALLOW;
        if (!(pathsObj instanceof List<?> pathsList) || pathsList.isEmpty()) return Verdict.ALLOW;

        String path = extractPath(argumentsJson);
        if (path == null || path.isBlank()) return Verdict.DENY;

        for (Object pattern : pathsList) {
            if (pattern instanceof String p) {
                if (p.contains("*")) {
                    String regex = p.replace(".", "\\.").replace("**", ".*").replace("*", "[^/]*");
                    if (path.matches(regex)) return Verdict.ALLOW;
                } else {
                    if (path.startsWith(p)) return Verdict.ALLOW;
                }
            }
        }
        return Verdict.DENY;
    }

    static String extractPath(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            Object path = args.get("file_path");
            if (path == null) path = args.get("path");
            return path instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseScope(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ActionScopeResolver] scope 파싱 실패: {}", e.getMessage());
            return Map.of();
        }
    }
}
