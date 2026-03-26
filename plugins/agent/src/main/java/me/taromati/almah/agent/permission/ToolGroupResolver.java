package me.taromati.almah.agent.permission;

import me.taromati.almah.llm.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 도구 그룹 확장 + 와일드카드 매칭.
 * "group:web" → [web_search, web_fetch], "mcp_omnifocus_*" → 접두어 매칭.
 */
@Component
public class ToolGroupResolver {

    private static final String GROUP_PREFIX = "group:";

    /**
     * allow/escalate 목록의 그룹 참조를 실제 도구명으로 확장한다.
     * 와일드카드 항목("mcp_*")은 확장하지 않고 그대로 유지 (매칭 시 확인).
     *
     * @param entries 도구명, 그룹 참조("group:web"), 와일드카드("mcp_*") 혼합 목록
     * @param groups  그룹 정의 맵
     * @return 확장된 도구명 + 와일드카드 집합
     */
    public Set<String> resolve(List<String> entries, Map<String, List<String>> groups) {
        if (entries == null || entries.isEmpty()) return Set.of();

        Set<String> resolved = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry.startsWith(GROUP_PREFIX)) {
                String groupName = entry.substring(GROUP_PREFIX.length());
                List<String> members = groups != null ? groups.get(groupName) : null;
                if (members != null) {
                    resolved.addAll(members);
                }
                // 미정의 그룹은 무시 (로그 경고는 ContextPolicyResolver에서)
            } else {
                resolved.add(entry);
            }
        }
        return resolved;
    }

    /**
     * 도구명이 확장된 allow 집합에 매칭되는지 확인한다.
     *
     * @param toolName      확인할 도구명
     * @param resolvedAllow resolve()로 확장된 집합
     * @return 매칭 여부
     */
    public boolean matches(String toolName, Set<String> resolvedAllow) {
        if (resolvedAllow == null || resolvedAllow.isEmpty()) return false;

        // "*" → 전체 허용
        if (resolvedAllow.contains("*")) return true;

        // 정확한 이름 매칭
        if (resolvedAllow.contains(toolName)) return true;

        // 와일드카드 접두어 매칭
        for (String pattern : resolvedAllow) {
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (toolName.startsWith(prefix)) return true;
            }
        }

        return false;
    }

    /**
     * 와일드카드/그룹이 포함된 allow 집합을 ToolRegistry의 실제 등록 도구로 확장한다.
     * LLM 도구 목록 생성 시 사용.
     */
    public Set<String> expandForLlmToolList(Set<String> resolvedAllow, ToolRegistry registry) {
        if (resolvedAllow == null || resolvedAllow.isEmpty()) return Set.of();

        Set<String> expanded = new LinkedHashSet<>();
        for (String toolName : registry.getActiveToolNames()) {
            if (matches(toolName, resolvedAllow)) {
                expanded.add(toolName);
            }
        }
        return expanded;
    }
}
