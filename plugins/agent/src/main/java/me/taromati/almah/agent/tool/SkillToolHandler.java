package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.service.GatingResult;
import me.taromati.almah.agent.service.SkillImporter;
import me.taromati.almah.agent.service.SkillManager;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill 관리 도구 — 스킬 목록/활성화/비활성화/추가/제거/설치/검색.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SkillToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("skill")
                            .description("스킬 관리 (목록/조회/활성화/비활성화/추가/제거/설치/검색)")
                            .parameters(buildParameters())
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final SkillImporter skillImporter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillToolHandler(ToolRegistry toolRegistry, SkillManager skillManager, SkillImporter skillImporter) {
        this.toolRegistry = toolRegistry;
        this.skillManager = skillManager;
        this.skillImporter = skillImporter;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("skill", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String action = (String) args.get("action");
            if (action == null) return ToolResult.text("action이 필요합니다");

            return switch (action) {
                case "list" -> handleList();
                case "view" -> handleView(args);
                case "enable" -> handleEnable(args);
                case "disable" -> handleDisable(args);
                case "add" -> handleAdd(args);
                case "remove" -> handleRemove(args);
                case "install" -> handleInstall(args);
                case "search" -> handleSearch(args);
                default -> ToolResult.text("알 수 없는 action: " + action);
            };
        } catch (Exception e) {
            log.error("[SkillToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("스킬 도구 오류: " + e.getMessage());
        }
    }

    private ToolResult handleList() {
        var all = skillManager.getAll();
        if (all.isEmpty()) {
            return ToolResult.text("등록된 스킬이 없습니다.");
        }

        StringBuilder sb = new StringBuilder("스킬 목록:\n");
        for (var cached : all) {
            var skill = cached.skillFile();
            var gating = cached.gatingResult();
            sb.append("- ").append(skill.name());
            sb.append(" [").append(gating.status().name()).append("]");
            if (skill.description() != null) {
                sb.append(": ").append(skill.description());
            }
            if (gating.reason() != null) {
                sb.append(" (").append(gating.reason()).append(")");
            }
            if (gating.status() == GatingResult.GatingStatus.INSTALL_REQUIRED && gating.installSpecs() != null) {
                for (var spec : gating.installSpecs()) {
                    sb.append("\n  설치: ").append(spec.kind()).append(" install ").append(spec.formula());
                    if (spec.label() != null) sb.append(" — ").append(spec.label());
                }
            }
            if (!skill.tools().isEmpty()) {
                sb.append("\n  도구: ").append(String.join(", ", skill.tools()));
            }
            if (skill.mcpServer() != null) {
                sb.append("\n  MCP: ").append(skill.mcpServer());
            }
            sb.append("\n");
        }
        return ToolResult.text(sb.toString().trim());
    }

    private ToolResult handleView(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");

        var cached = skillManager.get(name);
        if (cached == null) return ToolResult.text("스킬을 찾을 수 없습니다: " + name);

        var skill = cached.skillFile();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(skill.name()).append("\n");
        if (skill.description() != null) {
            sb.append("설명: ").append(skill.description()).append("\n");
        }
        sb.append("상태: ").append(cached.gatingResult().status().name()).append("\n");
        if (cached.gatingResult().status() == GatingResult.GatingStatus.INSTALL_REQUIRED
                && cached.gatingResult().installSpecs() != null) {
            sb.append("설치 방법:\n");
            for (var spec : cached.gatingResult().installSpecs()) {
                sb.append("  - ").append(spec.kind()).append(" install ").append(spec.formula());
                if (spec.label() != null) sb.append(" — ").append(spec.label());
                sb.append("\n");
            }
        }
        if (!skill.tools().isEmpty()) {
            sb.append("도구: ").append(String.join(", ", skill.tools())).append("\n");
        }
        if (skill.mcpServer() != null) {
            sb.append("MCP: ").append(skill.mcpServer()).append("\n");
        }
        sb.append("\n").append(skill.content());
        return ToolResult.text(sb.toString());
    }

    private ToolResult handleEnable(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");

        try {
            skillManager.enable(name);
            var cached = skillManager.get(name);
            String status = cached != null ? cached.gatingResult().status().name() : "UNKNOWN";
            return ToolResult.text("스킬 '" + name + "' 활성화됨 (게이팅: " + status + ")");
        } catch (Exception e) {
            return ToolResult.text("스킬 활성화 실패: " + e.getMessage());
        }
    }

    private ToolResult handleDisable(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");

        try {
            skillManager.disable(name);
            return ToolResult.text("스킬 '" + name + "' 비활성화됨");
        } catch (Exception e) {
            return ToolResult.text("스킬 비활성화 실패: " + e.getMessage());
        }
    }

    private ToolResult handleAdd(Map<String, Object> args) {
        String name = (String) args.get("name");
        String content = (String) args.get("content");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");
        if (content == null || content.isBlank()) return ToolResult.text("content가 필요합니다");

        try {
            skillManager.create(name, content);
            var cached = skillManager.get(name);
            String status = cached != null ? cached.gatingResult().status().name() : "UNKNOWN";
            return ToolResult.text("스킬 '" + name + "' 생성됨 (게이팅: " + status + ")");
        } catch (Exception e) {
            return ToolResult.text("스킬 생성 실패: " + e.getMessage());
        }
    }

    private ToolResult handleRemove(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) return ToolResult.text("name이 필요합니다");

        try {
            skillManager.remove(name);
            return ToolResult.text("스킬 '" + name + "' 제거됨");
        } catch (Exception e) {
            return ToolResult.text("스킬 제거 실패: " + e.getMessage());
        }
    }

    private ToolResult handleInstall(Map<String, Object> args) {
        String source = (String) args.getOrDefault("source", "github");
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) return ToolResult.text("url이 필요합니다");

        if (skillManager.wasInstallRejected(url)) {
            return ToolResult.text("이 URL의 스킬 설치는 사용자에 의해 거부되었습니다: " + url);
        }

        try {
            String skillName = skillImporter.importSkill(source, url);
            var cached = skillManager.get(skillName);
            String status = cached != null ? cached.gatingResult().status().name() : "UNKNOWN";
            return ToolResult.text("스킬 '" + skillName + "' 설치됨 (게이팅: " + status + ")");
        } catch (Exception e) {
            return ToolResult.text("스킬 설치 실패: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult handleSearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) return ToolResult.text("query가 필요합니다");

        try {
            var results = skillImporter.searchClawHub(query);
            if (results.isEmpty()) {
                return ToolResult.text("'" + query + "' 검색 결과가 없습니다.");
            }

            StringBuilder sb = new StringBuilder("ClawHub 검색 결과 (\"" + query + "\"):\n");
            for (var item : results) {
                sb.append("- **").append(item.getOrDefault("displayName", "")).append("**");
                sb.append(" (slug: ").append(item.getOrDefault("slug", "")).append(")");
                Object version = item.get("version");
                if (version != null) sb.append(" v").append(version);
                sb.append("\n");
                Object summary = item.get("summary");
                if (summary != null) sb.append("  ").append(summary).append("\n");
            }
            sb.append("\n설치: action=install, source=clawhub, url={slug}");
            return ToolResult.text(sb.toString().trim());
        } catch (Exception e) {
            return ToolResult.text("ClawHub 검색 실패: " + e.getMessage());
        }
    }

    private static Map<String, Object> buildParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "수행할 작업",
                "enum", List.of("list", "view", "enable", "disable", "add", "remove", "install", "search")));
        properties.put("name", Map.of("type", "string",
                "description", "스킬 이름 (view/enable/disable/add/remove)"));
        properties.put("content", Map.of("type", "string",
                "description", "SKILL.md 전체 내용 (add)"));
        properties.put("source", Map.of("type", "string",
                "description", "소스 유형 (install). clawhub은 slug, github/url은 URL",
                "enum", List.of("github", "url", "clawhub")));
        properties.put("url", Map.of("type", "string",
                "description", "스킬 소스 URL 또는 ClawHub slug (install)"));
        properties.put("query", Map.of("type", "string",
                "description", "ClawHub 검색어 (search)"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", List.of("action"));
        return params;
    }
}
