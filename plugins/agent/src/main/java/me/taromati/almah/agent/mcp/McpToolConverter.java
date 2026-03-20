package me.taromati.almah.agent.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest.ToolDefinition;
import me.taromati.almah.llm.tool.ToolResult;

import java.util.*;

/**
 * MCP Tool ↔ OpenAI ToolDefinition 변환 유틸리티.
 */
public final class McpToolConverter {

    private McpToolConverter() {}

    /**
     * MCP Tool → OpenAI ToolDefinition 변환.
     * MCP inputSchema와 OpenAI parameters는 모두 JSON Schema이므로 구조 동일.
     */
    public static ToolDefinition convert(String namespacedName, McpSchema.Tool tool) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        McpSchema.JsonSchema schema = tool.inputSchema();
        if (schema != null) {
            if (schema.type() != null) parameters.put("type", schema.type());
            if (schema.properties() != null) parameters.put("properties", schema.properties());
            if (schema.required() != null) parameters.put("required", schema.required());
            if (schema.additionalProperties() != null)
                parameters.put("additionalProperties", schema.additionalProperties());
        }
        if (parameters.isEmpty()) {
            parameters.put("type", "object");
            parameters.put("properties", Map.of());
        }

        String description = tool.description();
        if (description == null || description.isBlank()) {
            description = tool.name();
        }

        return ToolDefinition.builder()
                .type("function")
                .function(ToolDefinition.Function.builder()
                        .name(namespacedName)
                        .description(description)
                        .parameters(parameters)
                        .build())
                .build();
    }

    /**
     * MCP CallToolResult → ToolResult 변환.
     * TextContent를 결합하고, ImageContent의 base64 데이터를 추출.
     */
    public static ToolResult convertResult(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return ToolResult.text("(결과 없음)");
        }

        StringBuilder textBuilder = new StringBuilder();
        byte[] imageData = null;

        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (!textBuilder.isEmpty()) textBuilder.append("\n");
                textBuilder.append(tc.text());
            } else if (content instanceof McpSchema.ImageContent ic) {
                if (imageData == null && ic.data() != null) {
                    try {
                        imageData = Base64.getDecoder().decode(ic.data());
                    } catch (IllegalArgumentException ignored) {
                        // base64 디코딩 실패 시 무시
                    }
                }
            }
        }

        if (Boolean.TRUE.equals(result.isError())) {
            String errorText = textBuilder.isEmpty() ? "MCP 도구 실행 오류" : textBuilder.toString();
            return ToolResult.text("[오류] " + errorText);
        }

        String text = textBuilder.isEmpty() ? "(결과 없음)" : textBuilder.toString();
        if (imageData != null) {
            return ToolResult.withImage(text, imageData);
        }
        return ToolResult.text(text);
    }

    /**
     * MCP 도구 이름을 네임스페이스 포함 이름으로 변환.
     * OpenAI 호환: [a-zA-Z0-9_-] 만 허용.
     */
    public static String namespacedName(String serverName, String toolName) {
        return "mcp_" + sanitize(serverName) + "_" + sanitize(toolName);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
