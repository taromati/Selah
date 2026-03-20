package me.taromati.almah.agent.tool;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.permission.PermissionGate;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.messenger.PluginCommandHandler;
import me.taromati.almah.core.messenger.TypingHandle;
import me.taromati.almah.core.util.PluginMdc;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.tool.ToolExecutionFilter;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 도구 관련 명령어 핸들러.
 * <ul>
 *   <li>{@code !도구 목록} — 등록된 도구 전체 목록</li>
 *   <li>{@code !도구 <도구명> <인자>} — 도구 직접 실행 (한글 별칭 지원)</li>
 *   <li>{@code !<도구명> <인자>} — 축약형 도구 직접 실행</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ToolCommandListener implements PluginCommandHandler {

    /** 한글 도구 별칭 -> 영어 도구명 */
    static final Map<String, String> TOOL_ALIASES = Map.ofEntries(
            Map.entry("스킬", "skill"),
            Map.entry("검색", "web_search"),
            Map.entry("가계부", "finance"),
            Map.entry("실행", "exec"),
            Map.entry("파일", "file_read"),
            Map.entry("브라우저", "browser"),
            Map.entry("크론", "cron")
    );

    /** 영어 도구명 -> 한글 별칭 (역방향 조회용) */
    static final Map<String, String> TOOL_ALIASES_REVERSE = TOOL_ALIASES.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    private final ToolRegistry toolRegistry;
    private final PermissionGate permissionGate;
    private final MessengerGatewayRegistry messengerRegistry;

    public ToolCommandListener(
            ToolRegistry toolRegistry,
            PermissionGate permissionGate,
            MessengerGatewayRegistry messengerRegistry
    ) {
        this.toolRegistry = toolRegistry;
        this.permissionGate = permissionGate;
        this.messengerRegistry = messengerRegistry;
    }

    @Override
    public boolean canHandle(String content) {
        if (content.startsWith("!도구")) return true;

        // !{도구명} 축약형 — !로 시작하지만 알려진 명령어가 아닌 경우
        if (content.startsWith("!") && content.length() > 1) {
            String first = content.substring(1).split("\\s+", 2)[0];
            // 도구 레지스트리 또는 별칭에 있으면 처리
            String resolved = TOOL_ALIASES.getOrDefault(first, first);
            return toolRegistry.getRegisteredToolNames().contains(resolved);
        }
        return false;
    }

    @Override
    public void handle(ChannelRef channel, String content, String channelId) {
        if (content.startsWith("!도구")) {
            handleToolPrefixCommand(channel, content, channelId);
        } else if (content.startsWith("!") && content.length() > 1) {
            String[] parts = content.substring(1).split("\\s+", 2);
            String toolName = parts[0];
            String toolArgs = parts.length > 1 ? parts[1] : "";
            handleDirectTool(channel, toolName, toolArgs, channelId);
        }
    }

    // ─── !도구 접두사 명령어 ───

    private void handleToolPrefixCommand(ChannelRef channel, String content, String channelId) {
        String rest = content.substring("!도구".length()).trim();

        if (rest.isEmpty() || rest.equals("목록") || rest.equals("list")) {
            showToolList(channel);
            return;
        }

        String[] parts = rest.split("\\s+", 2);
        String toolName = TOOL_ALIASES.getOrDefault(parts[0], parts[0]);
        String toolArgs = parts.length > 1 ? parts[1] : "";
        handleDirectTool(channel, toolName, toolArgs, channelId);
    }

    // ─── 도구 직접 실행 ───

    private void handleDirectTool(ChannelRef channel, String toolName, String rawArgs, String channelId) {
        toolName = TOOL_ALIASES.getOrDefault(toolName, toolName);
        String resolvedToolName = toolName;

        try (TypingHandle ignored = messengerRegistry.startTyping(channel)) {
            PluginMdc.set("agent");
            try {
                String argumentsJson;
                if (rawArgs.isEmpty()) {
                    argumentsJson = "{}";
                } else if (rawArgs.startsWith("{")) {
                    argumentsJson = rawArgs;
                } else {
                    argumentsJson = "{\"prompt\":\"" + rawArgs.replace("\"", "\\\"") + "\"}";
                }

                ToolExecutionFilter filter = permissionGate.createChatFilter(null, channel);
                String blocked = filter.checkPermission(resolvedToolName, argumentsJson);
                if (blocked != null) {
                    messengerRegistry.sendText(channel, "\u26D4 " + blocked);
                    return;
                }

                log.info("[ToolCommandListener] Direct tool: {} args={}", resolvedToolName, StringUtils.truncate(argumentsJson, 80));
                ToolResult result = toolRegistry.execute(resolvedToolName, argumentsJson);

                if (result.hasImage()) {
                    messengerRegistry.sendWithImages(channel,
                            "\uD83D\uDD27 `" + resolvedToolName + "`: " + result.getText(),
                            List.of(result.getImage()));
                } else {
                    messengerRegistry.sendText(channel, "\uD83D\uDD27 `" + resolvedToolName + "`: " + result.getText());
                }
            } catch (Exception e) {
                log.error("[ToolCommandListener] Tool execution error", e);
                messengerRegistry.sendText(channel, "\u274C 도구 실행 오류: " + e.getMessage());
            } finally {
                PluginMdc.clear();
            }
        }
    }

    // ─── 도구 목록 ───

    private void showToolList(ChannelRef channel) {
        Map<String, String> toolDescs = toolRegistry.getToolDescriptions();

        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("핵심", new ArrayList<>());
        categories.put("파일/코드", new ArrayList<>());
        categories.put("메모리", new ArrayList<>());
        categories.put("MCP", new ArrayList<>());

        for (var entry : toolDescs.entrySet()) {
            String name = entry.getKey();
            String desc = StringUtils.truncate(entry.getValue(), 60);
            String alias = TOOL_ALIASES_REVERSE.get(name);
            String display = alias != null
                    ? "`" + name + "` (`" + alias + "`) \u2014 " + desc
                    : "`" + name + "` \u2014 " + desc;

            if (name.startsWith("mcp_")) {
                categories.get("MCP").add(display);
            } else if (name.startsWith("memory_")) {
                categories.get("메모리").add(display);
            } else if (name.equals("file_read") || name.equals("file_write") || name.equals("edit")
                    || name.equals("glob") || name.equals("grep") || name.equals("exec")) {
                categories.get("파일/코드").add(display);
            } else {
                categories.get("핵심").add(display);
            }
        }

        StringBuilder sb = new StringBuilder("**등록된 도구 목록**\n\n");
        for (var cat : categories.entrySet()) {
            if (cat.getValue().isEmpty()) continue;
            sb.append("**[").append(cat.getKey()).append("]**\n");
            for (String line : cat.getValue()) {
                sb.append(line).append("\n");
            }
            sb.append("\n");
        }
        sb.append("사용법: `!도구 <도구명> <인자>` 또는 `!<도구명> <인자>`");

        messengerRegistry.sendText(channel, sb.toString());
    }

    /**
     * 도구 별칭 맵 반환 (도움말 생성용).
     */
    public static Map<String, String> getToolAliases() {
        return TOOL_ALIASES;
    }
}
