package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.mcp.McpClientManager;
import me.taromati.almah.agent.permission.ToolPolicyService;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tool_search: 통합 카탈로그(내장 discoverable + MCP deferred)에서 도구를 검색하고 동적 로드한다.
 * 기존 mcp_tools_load의 기능을 흡수한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ToolSearchHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("tool_search")
                            .description("도구 카탈로그에서 도구를 검색하고 현재 세션에 로드합니다. query/tools/server 중 하나 이상 필수.")
                            .parameters(buildParameters())
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final McpClientManager mcpClientManager;
    private final ToolPolicyService toolPolicyService;
    private final ObjectMapper objectMapper;

    public ToolSearchHandler(ToolRegistry toolRegistry, McpClientManager mcpClientManager,
                             ToolPolicyService toolPolicyService, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.mcpClientManager = mcpClientManager;
        this.toolPolicyService = toolPolicyService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("tool_search", DEFINITION, this::execute);
    }

    @SuppressWarnings("unchecked")
    ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String query = (String) args.get("query");
            List<String> toolNames = (List<String>) args.get("tools");
            String server = (String) args.get("server");
            Boolean loadParam = (Boolean) args.get("load");
            boolean load = loadParam == null || loadParam; // 기본: true

            // 파라미터 검증
            boolean hasQuery = query != null && !query.isBlank();
            boolean hasTools = toolNames != null && !toolNames.isEmpty();
            boolean hasServer = server != null && !server.isBlank();

            if (!hasQuery && !hasTools && !hasServer) {
                return ToolResult.text("query, tools, server 중 하나 이상의 파라미터를 지정하세요.");
            }

            // 매칭 도구 수집
            List<String> matched = new ArrayList<>();

            if (hasQuery) {
                matched.addAll(toolRegistry.searchDeferredTools(query));
            }

            if (hasTools) {
                for (String name : toolNames) {
                    if (toolRegistry.getDefinition(name) != null && !matched.contains(name)) {
                        matched.add(name);
                    }
                }
            }

            if (hasServer) {
                List<String> serverTools = mcpClientManager.getServerTools(server);
                for (String name : serverTools) {
                    if (!matched.contains(name)) {
                        matched.add(name);
                    }
                }
            }

            // deny 정책 필터링
            List<String> loaded = new ArrayList<>();
            List<String> denied = new ArrayList<>();

            for (String name : matched) {
                if ("deny".equals(toolPolicyService.getPolicy(name))) {
                    denied.add(name);
                } else {
                    loaded.add(name);
                }
            }

            if (loaded.isEmpty() && denied.isEmpty()) {
                return ToolResult.text("매칭되는 도구가 없습니다.");
            }

            // 응답 텍스트 구성
            StringBuilder sb = new StringBuilder();
            if (!loaded.isEmpty()) {
                sb.append(loaded.size()).append("개 도구");
                if (load) {
                    sb.append(" 로드됨 (다음 턴부터 사용 가능):");
                } else {
                    sb.append(" 발견됨:");
                }
                for (String name : loaded) {
                    var def = toolRegistry.getDefinition(name);
                    String desc = def != null && def.getFunction() != null ? def.getFunction().getDescription() : null;
                    sb.append("\n- ").append(name);
                    if (desc != null) sb.append(": ").append(desc);
                }
            }
            if (!denied.isEmpty()) {
                sb.append("\n\n접근 거부된 도구: ").append(denied);
            }

            log.info("[ToolSearchHandler] Matched {} tools (loaded={}, denied={}): {}",
                    matched.size(), loaded.size(), denied.size(), loaded);

            if (load && !loaded.isEmpty()) {
                return ToolResult.withLoadTools(sb.toString(), loaded);
            }
            return ToolResult.text(sb.toString());

        } catch (Exception e) {
            log.error("[ToolSearchHandler] Error: {}", e.getMessage());
            return ToolResult.text("도구 검색 오류: " + e.getMessage());
        }
    }

    private static Map<String, Object> buildParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "키워드 검색 (도구 이름/설명에서 매칭)"));
        properties.put("tools", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "직접 지정할 도구 이름 목록"));
        properties.put("server", Map.of(
                "type", "string",
                "description", "MCP 서버 이름 — 해당 서버의 모든 도구 로드"));
        properties.put("load", Map.of(
                "type", "boolean",
                "description", "true면 매칭된 도구를 세션에 로드 (기본: true)"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        return params;
    }
}
