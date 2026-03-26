package me.taromati.almah.llm.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool 정의 레지스트리 (Map 기반 동적 등록)
 * 각 ToolHandler가 @PostConstruct에서 register() 호출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final Map<String, ChatCompletionRequest.ToolDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();
    private final Set<String> deferredTools = ConcurrentHashMap.newKeySet();
    private final Map<String, String> toolCategories = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    /**
     * 도구 등록
     */
    public void register(String name, ChatCompletionRequest.ToolDefinition definition, ToolHandler handler) {
        register(name, definition, handler, false);
    }

    /**
     * 도구 등록 (deferred 플래그 지원).
     * deferred 도구는 getActiveToolNames()에서 제외되며, 동적 로딩으로만 LLM에 노출.
     */
    public void register(String name, ChatCompletionRequest.ToolDefinition definition, ToolHandler handler, boolean deferred) {
        definitions.put(name, definition);
        handlers.put(name, handler);
        if (deferred) {
            deferredTools.add(name);
        } else {
            deferredTools.remove(name);
        }
        log.info("[ToolRegistry] Registered tool: {}{}", name, deferred ? " (deferred)" : "");
    }

    /**
     * 도구 등록 (deferred + 카테고리 지원).
     * 카테고리는 시스템 프롬프트 카탈로그에서 그룹핑에 사용.
     */
    public void register(String name, ChatCompletionRequest.ToolDefinition definition, ToolHandler handler,
                         boolean deferred, String category) {
        register(name, definition, handler, deferred);
        if (category != null) {
            toolCategories.put(name, category);
        }
    }

    /**
     * 도구 등록 해제
     */
    public void unregister(String name) {
        definitions.remove(name);
        handlers.remove(name);
        deferredTools.remove(name);
        log.info("[ToolRegistry] Unregistered tool: {}", name);
    }

    /**
     * 여러 도구 일괄 등록 해제
     */
    public void unregisterAll(List<String> names) {
        names.forEach(this::unregister);
    }

    /**
     * 도구 실행 — 실행 전에 스키마 검증으로 알 수 없는 파라미터를 감지합니다.
     * 도구 이름이 정확히 매칭되지 않으면 Levenshtein 거리 기반 퍼지 매칭을 시도합니다.
     */
    public ToolResult execute(String toolName, String argumentsJson) {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            var candidates = findClosestTools(toolName);
            if (candidates.size() == 1 && levenshteinDistance(toolName, candidates.getFirst()) <= 2) {
                // 거리 2 이하 단일 후보: 자동 보정
                String corrected = candidates.getFirst();
                log.info("[ToolRegistry] Fuzzy match: '{}' → '{}'", toolName, corrected);
                handler = handlers.get(corrected);
                String notice = "⚠️ 도구 이름 보정: " + toolName + " → " + corrected;
                String schemaWarning = validateArguments(corrected, argumentsJson);
                ToolResult result = handler.execute(argumentsJson);
                String prefix = schemaWarning != null ? notice + "\n" + schemaWarning : notice;
                return ToolResult.text(prefix + "\n\n" + result.getText());
            }
            if (!candidates.isEmpty()) {
                log.warn("[ToolRegistry] Unknown tool: '{}', suggestions: {}", toolName, candidates);
                return ToolResult.text("알 수 없는 도구: " + toolName + "\n유사한 도구: " + String.join(", ", candidates));
            }
            log.warn("[ToolRegistry] Unknown tool: {}", toolName);
            return ToolResult.text("알 수 없는 도구: " + toolName);
        }

        // 스키마 검증: 알 수 없는 파라미터 감지
        String schemaWarning = validateArguments(toolName, argumentsJson);

        ToolResult result = handler.execute(argumentsJson);

        if (schemaWarning != null) {
            // 경고를 결과 앞에 붙여서 LLM이 인지하도록 함
            return ToolResult.text(schemaWarning + "\n\n" + result.getText());
        }
        return result;
    }

    /**
     * 도구 호출 인자를 스키마와 대조하여 알 수 없는 파라미터가 있으면 경고 문자열 반환.
     * 정상이면 null 반환.
     */
    @SuppressWarnings("unchecked")
    private String validateArguments(String toolName, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return null;

        ChatCompletionRequest.ToolDefinition def = definitions.get(toolName);
        if (def == null || def.getFunction() == null || def.getFunction().getParameters() == null) return null;

        Map<String, Object> params = def.getFunction().getParameters();
        Object propsObj = params.get("properties");
        if (!(propsObj instanceof Map<?, ?> properties)) return null;

        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            Set<String> validKeys = new HashSet<>();
            for (Object key : properties.keySet()) {
                validKeys.add(String.valueOf(key));
            }

            List<String> unknownKeys = new ArrayList<>();
            for (String key : args.keySet()) {
                if (!validKeys.contains(key)) {
                    unknownKeys.add(key);
                }
            }

            if (!unknownKeys.isEmpty()) {
                log.warn("[ToolRegistry] Tool '{}' received unknown parameters: {} (valid: {})",
                        toolName, unknownKeys, validKeys);
                return "⚠️ 알 수 없는 파라미터: " + unknownKeys +
                        " → 이 파라미터는 무시됩니다. 유효한 파라미터: " + validKeys +
                        ". 의도한 파라미터 이름이 맞는지 확인하세요.";
            }
        } catch (Exception e) {
            // JSON 파싱 실패 시 검증 건너뛰기
            log.debug("[ToolRegistry] Schema validation skipped for '{}': {}", toolName, e.getMessage());
        }
        return null;
    }

    /**
     * 표준 DP 기반 Levenshtein 거리 계산
     */
    static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                        dp[i - 1][j] + 1,       // 삭제
                        dp[i][j - 1] + 1),       // 삽입
                        dp[i - 1][j - 1] + cost  // 치환
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    /**
     * 등록된 도구 중 Levenshtein 거리 3 이하인 후보를 거리순으로 최대 3개 반환
     */
    private List<String> findClosestTools(String toolName) {
        record Candidate(String name, int distance) {}
        List<Candidate> matches = new ArrayList<>();
        for (String registered : handlers.keySet()) {
            int dist = levenshteinDistance(toolName, registered);
            if (dist <= 3) {
                matches.add(new Candidate(registered, dist));
            }
        }
        matches.sort(Comparator.comparingInt(Candidate::distance));
        return matches.stream().map(Candidate::name).limit(3).toList();
    }

    /**
     * 이름 목록으로 정의 조회
     */
    public List<ChatCompletionRequest.ToolDefinition> getTools(List<String> toolNames) {
        List<ChatCompletionRequest.ToolDefinition> tools = new ArrayList<>();
        for (String name : toolNames) {
            ChatCompletionRequest.ToolDefinition def = definitions.get(name);
            if (def != null) {
                tools.add(def);
            }
        }
        return tools;
    }

    /**
     * 등록된 도구 이름→설명 맵 (도움말 출력용)
     */
    public Map<String, String> getToolDescriptions() {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : definitions.entrySet()) {
            var func = entry.getValue().getFunction();
            result.put(entry.getKey(), func != null ? func.getDescription() : "(설명 없음)");
        }
        return result;
    }

    /**
     * 지정된 도구들의 정의 텍스트 총 문자 수 (budget 추정용)
     */
    public int estimateDefinitionChars(List<String> toolNames) {
        int chars = 0;
        for (String name : toolNames) {
            ChatCompletionRequest.ToolDefinition def = definitions.get(name);
            if (def == null || def.getFunction() == null) continue;
            chars += 20; // type, structure overhead
            var func = def.getFunction();
            if (func.getName() != null) chars += func.getName().length();
            if (func.getDescription() != null) chars += func.getDescription().length();
            if (func.getParameters() != null) chars += func.getParameters().toString().length();
        }
        return chars;
    }

    /**
     * 단일 도구 정의 조회
     */
    public ChatCompletionRequest.ToolDefinition getDefinition(String name) {
        return definitions.get(name);
    }

    /**
     * 활성(non-deferred) 도구 이름 목록. LLM의 tools 배열에 포함할 도구.
     */
    public List<String> getActiveToolNames() {
        return definitions.keySet().stream()
                .filter(name -> !deferredTools.contains(name))
                .toList();
    }

    /**
     * Deferred 도구 카탈로그 (이름 → 설명). 시스템 프롬프트 주입용.
     */
    public Map<String, String> getDeferredToolCatalog() {
        Map<String, String> catalog = new LinkedHashMap<>();
        for (String name : deferredTools) {
            var def = definitions.get(name);
            if (def != null && def.getFunction() != null) {
                catalog.put(name, def.getFunction().getDescription());
            }
        }
        return catalog;
    }

    /**
     * Deferred 도구 카탈로그를 카테고리별로 그룹핑하여 반환.
     * 시스템 프롬프트 주입용.
     */
    public Map<String, Map<String, String>> getDeferredToolCatalogByCategory() {
        Map<String, Map<String, String>> catalog = new LinkedHashMap<>();
        for (String name : deferredTools) {
            var def = definitions.get(name);
            if (def == null || def.getFunction() == null) continue;
            String category = toolCategories.getOrDefault(name, "기타");
            catalog.computeIfAbsent(category, k -> new LinkedHashMap<>())
                    .put(name, def.getFunction().getDescription());
        }
        return catalog;
    }

    /**
     * 키워드로 deferred 도구를 검색.
     * 도구 이름과 설명에서 키워드 매칭 (대소문자 무시).
     */
    public List<String> searchDeferredTools(String query) {
        String lowerQuery = query.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String name : deferredTools) {
            var def = definitions.get(name);
            if (def == null || def.getFunction() == null) continue;
            String desc = def.getFunction().getDescription();
            if (name.toLowerCase().contains(lowerQuery) ||
                (desc != null && desc.toLowerCase().contains(lowerQuery))) {
                matches.add(name);
            }
        }
        return matches;
    }

    /**
     * 등록된 모든 도구 목록
     */
    public List<ChatCompletionRequest.ToolDefinition> getAllTools() {
        return new ArrayList<>(definitions.values());
    }

    /**
     * 등록된 도구 이름 목록 (deferred 포함)
     */
    public List<String> getRegisteredToolNames() {
        return new ArrayList<>(definitions.keySet());
    }
}
