package me.taromati.almah.agent.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.service.PersistentContextReader;
import me.taromati.almah.agent.service.SkillFile;
import me.taromati.almah.agent.service.SkillManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import me.taromati.almah.agent.tool.ExecToolHandler;
import me.taromati.almah.llm.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ActionScope JSON 생성 및 확장.
 * 전역 기본값(config.yml 대응)에서 초기 scope를 생성하고,
 * 승인 시 scope를 확장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ActionScopeFactory {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentConfigProperties config;

    @Autowired(required = false)
    private PersistentContextReader persistentContextReader;

    @Autowired(required = false)
    private SkillManager skillManager;

    @Autowired
    private ToolRegistry toolRegistry;

    /**
     * 전역 기본 ActionScope JSON 생성.
     * policy=allow인 도구 + 활성 스킬 도구.
     */
    public String createDefaultScope() {
        Map<String, Object> scope = new LinkedHashMap<>();
        for (var entry : config.getTools().getPolicy().entrySet()) {
            if ("allow".equals(entry.getValue())) {
                scope.put(entry.getKey(), true);
            }
        }

        // Active 스킬의 tools 자동 포함 — SkillManager 우선, fallback으로 PersistentContextReader
        try {
            List<SkillFile> skills = skillManager != null
                    ? skillManager.getActiveSkills()
                    : (persistentContextReader != null ? persistentContextReader.readActiveSkills() : List.of());
            for (var skill : skills) {
                if (skill.tools() != null) {
                    for (String tool : skill.tools()) {
                        scope.put(tool, true);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ActionScopeFactory] 스킬 로드 실패: {}", e.getMessage());
        }

        return serialize(scope);
    }

    /**
     * 루틴/제안 Task용 ActionScope JSON 생성.
     * config.routine.scope 설정이 있으면 와일드카드 매칭, 없으면 createDefaultScope() 폴백.
     */
    public String createRoutineScope() {
        var routineConfig = config.getRoutine();
        if (routineConfig == null || routineConfig.getScope() == null) {
            return createDefaultScope();
        }
        return buildScopeFromConfig(routineConfig.getScope(), "routineScope");
    }

    /**
     * Cron 잡용 ActionScope JSON 생성.
     * config.cron.scope 설정이 있으면 와일드카드 매칭, 비어있으면 routineScope 폴백.
     */
    public String createCronScope() {
        var cronScopeConfig = config.getCron().getScope();
        if (cronScopeConfig == null
                || (cronScopeConfig.getAllow().isEmpty() && cronScopeConfig.getAsk().isEmpty())) {
            return createRoutineScope();
        }
        return buildScopeFromConfig(cronScopeConfig, "cronScope");
    }

    /**
     * RoutineScopeConfig 기반 ActionScope JSON 생성 (routine/cron 공통).
     */
    private String buildScopeFromConfig(AgentConfigProperties.RoutineConfig.RoutineScopeConfig scopeConfig,
                                         String label) {
        Map<String, Object> scope = new LinkedHashMap<>();

        try {
            List<String> registeredTools = toolRegistry != null
                    ? toolRegistry.getRegisteredToolNames() : List.of();

            // allow 패턴 매칭
            for (String pattern : scopeConfig.getAllow()) {
                matchAndAdd(pattern, registeredTools, scope, true);
            }

            // ask 패턴 매칭
            for (String pattern : scopeConfig.getAsk()) {
                matchAndAdd(pattern, registeredTools, scope, "ask");
            }

            // _default: ask
            scope.put("_default", "ask");

            // 활성 스킬 도구 포함 (createDefaultScope와 동일)
            try {
                List<SkillFile> skills = skillManager != null
                        ? skillManager.getActiveSkills()
                        : (persistentContextReader != null ? persistentContextReader.readActiveSkills() : List.of());
                for (var skill : skills) {
                    if (skill.tools() != null) {
                        for (String tool : skill.tools()) {
                            scope.putIfAbsent(tool, true);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[ActionScopeFactory] 스킬 로드 실패: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("[ActionScopeFactory] {} 생성 실패, 기본값 폴백: {}", label, e.getMessage());
            return createDefaultScope();
        }

        return serialize(scope);
    }

    private void matchAndAdd(String pattern, List<String> registeredTools,
                              Map<String, Object> scope, Object value) {
        if (pattern.contains("*")) {
            String prefix = pattern.substring(0, pattern.indexOf('*'));
            boolean matched = false;
            for (String tool : registeredTools) {
                if (tool.startsWith(prefix)) {
                    scope.put(tool, value);
                    matched = true;
                }
            }
            if (!matched) {
                log.warn("[ActionScopeFactory] 와일드카드 '{}' 매칭 도구 없음", pattern);
            }
        } else {
            // 정확 매칭
            if (registeredTools.contains(pattern)) {
                scope.put(pattern, value);
            } else {
                log.warn("[ActionScopeFactory] 도구 '{}' 미등록", pattern);
            }
        }
    }

    /**
     * 제안용 scope (읽기 전용 도구만 허용).
     */
    public String createSuggestScope() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("web_search", true);
        scope.put("web_fetch", true);
        scope.put("glob", true);
        scope.put("grep", true);
        scope.put("file_read", true);
        scope.put("memory_search", true);
        scope.put("memory_get", true);
        scope.put("memory_explore", true);
        scope.put("memory_query", true);
        return serialize(scope);
    }

    /**
     * 대화용 확장 scope (기본 + 모든 도구 허용).
     * 대화 모드에서는 모든 도구가 기본 허용되고, Discord 승인으로 추가 제어.
     */
    public String createChatScope() {
        Map<String, Object> scope = new LinkedHashMap<>();

        // All tools pre-approved for chat
        scope.put("web_search", true);
        scope.put("web_fetch", true);
        scope.put("glob", true);
        scope.put("grep", true);
        scope.put("file_read", true);
        scope.put("file_write", true);
        scope.put("edit", true);
        scope.put("exec", true);
        scope.put("browser", true);
        scope.put("memory_search", true);
        scope.put("memory_store", true);
        scope.put("memory_get", true);
        scope.put("memory_explore", true);
        scope.put("memory_query", true);
        scope.put("cron", true);
        scope.put("spawn_subagent", true);
        scope.put("process", true);
        scope.put("mcp", true);

        return serialize(scope);
    }

    /**
     * 승인 후 scope 확장: 기존 scope에 도구 추가.
     *
     * @param currentScopeJson 현재 actionScope JSON
     * @param toolName         추가할 도구 이름
     * @return 확장된 actionScope JSON
     */
    public String expandScope(String currentScopeJson, String toolName) {
        return expandScope(currentScopeJson, toolName, null);
    }

    /**
     * 승인 후 scope 확장: 기존 scope에 도구 추가 (인자 기반 세분화).
     * exec일 때 arguments에서 command를 추출하여 allowlist 단위로 세분화.
     *
     * @param currentScopeJson 현재 actionScope JSON
     * @param toolName         추가할 도구 이름
     * @param argumentsJson    도구 인자 JSON (exec 세분화에 사용)
     * @return 확장된 actionScope JSON
     */
    @SuppressWarnings("unchecked")
    public String expandScope(String currentScopeJson, String toolName, String argumentsJson) {
        Map<String, Object> scope = parseScope(currentScopeJson);

        if ("exec".equals(toolName) && argumentsJson != null) {
            String command = ActionScopeResolver.extractCommand(argumentsJson);
            if (command != null && !command.isBlank()) {
                List<String> segments = ExecToolHandler.parseCommandSegments(command);
                // 기존 allowlist가 있으면 병합
                Object existing = scope.get("exec");
                List<String> allowlist = new ArrayList<>();
                if (existing instanceof Map<?, ?> conditions) {
                    Object existingList = conditions.get("allowlist");
                    if (existingList instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof String s && !allowlist.contains(s)) {
                                allowlist.add(s);
                            }
                        }
                    }
                }
                for (String seg : segments) {
                    if (!allowlist.contains(seg)) {
                        allowlist.add(seg);
                    }
                }
                scope.put("exec", Map.of("allowlist", allowlist));
                return serialize(scope);
            }
        }

        scope.put(toolName, true);
        return serialize(scope);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseScope(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String serialize(Map<String, Object> scope) {
        try {
            return objectMapper.writeValueAsString(scope);
        } catch (JsonProcessingException e) {
            log.warn("[ActionScopeFactory] scope 직렬화 실패: {}", e.getMessage());
            return "{}";
        }
    }
}
