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
 * mcp_tools_load 도구: MCP deferred 도구를 현재 턴에 동적 로딩.
 * LLM이 시스템 프롬프트의 카탈로그를 참고하여 필요한 도구를 지정하면,
 * ToolCallingService가 다음 라운드부터 해당 도구를 tools 배열에 추가합니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class McpToolsLoadHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("mcp_tools_load")
                            .description("MCP 도구를 현재 턴에 로드하여 사용 가능하게 합니다. 시스템 프롬프트의 MCP 도구 카탈로그를 참고하세요.")
                            .parameters(buildParameters())
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final McpClientManager mcpClientManager;
    private final ToolPolicyService toolPolicyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpToolsLoadHandler(ToolRegistry toolRegistry, McpClientManager mcpClientManager,
                                ToolPolicyService toolPolicyService) {
        this.toolRegistry = toolRegistry;
        this.mcpClientManager = mcpClientManager;
        this.toolPolicyService = toolPolicyService;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("mcp_tools_load", DEFINITION, this::execute);
    }

    @SuppressWarnings("unchecked")
    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String server = (String) args.get("server");
            List<String> toolNames = (List<String>) args.get("tools");

            if (server == null && (toolNames == null || toolNames.isEmpty())) {
                return ToolResult.text("server 또는 tools 중 하나를 지정하세요.");
            }

            // server 지정 시: 해당 서버의 모든 도구
            if (server != null) {
                if (!mcpClientManager.isConnected(server)) {
                    return ToolResult.text("연결되지 않은 서버: " + server);
                }
                toolNames = mcpClientManager.getServerTools(server);
                if (toolNames.isEmpty()) {
                    return ToolResult.text("서버 '" + server + "'에 등록된 도구가 없습니다.");
                }
            }

            // deny 정책 도구 필터링 + 존재 여부 검증
            List<String> loaded = new ArrayList<>();
            List<String> denied = new ArrayList<>();
            List<String> notFound = new ArrayList<>();

            for (String name : toolNames) {
                var def = toolRegistry.getDefinition(name);
                if (def == null) {
                    notFound.add(name);
                    continue;
                }
                if ("deny".equals(toolPolicyService.getPolicy(name))) {
                    denied.add(name);
                    continue;
                }
                loaded.add(name);
            }

            if (loaded.isEmpty()) {
                StringBuilder sb = new StringBuilder("로드할 도구가 없습니다.");
                if (!notFound.isEmpty()) sb.append("\n존재하지 않는 도구: ").append(notFound);
                if (!denied.isEmpty()) sb.append("\n접근 거부된 도구: ").append(denied);
                return ToolResult.text(sb.toString());
            }

            // 응답 텍스트 구성
            StringBuilder sb = new StringBuilder();
            sb.append(loaded.size()).append("개 도구 로드됨:");
            for (String name : loaded) {
                var def = toolRegistry.getDefinition(name);
                String desc = def.getFunction() != null ? def.getFunction().getDescription() : null;
                sb.append("\n- ").append(name);
                if (desc != null) sb.append(": ").append(desc);
            }
            if (!notFound.isEmpty()) sb.append("\n\n존재하지 않는 도구 (무시됨): ").append(notFound);
            if (!denied.isEmpty()) sb.append("\n\n접근 거부된 도구 (무시됨): ").append(denied);

            log.info("[McpToolsLoadHandler] Loaded {} tools: {}", loaded.size(), loaded);
            return ToolResult.withLoadTools(sb.toString(), loaded);

        } catch (Exception e) {
            log.error("[McpToolsLoadHandler] Error: {}", e.getMessage());
            return ToolResult.text("도구 로드 오류: " + e.getMessage());
        }
    }

    private static Map<String, Object> buildParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server", Map.of(
                "type", "string",
                "description", "MCP 서버 이름 — 해당 서버의 모든 도구를 로드"));
        properties.put("tools", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "로드할 MCP 도구 이름 목록 (mcp_{서버}_{도구} 형식)"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        return params;
    }
}
