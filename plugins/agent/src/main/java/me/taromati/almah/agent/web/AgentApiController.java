package me.taromati.almah.agent.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentActivityLogEntity;
import me.taromati.almah.agent.db.entity.AgentAuditLogEntity;
import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import me.taromati.almah.agent.db.entity.AgentRoutineHistoryEntity;
import me.taromati.almah.agent.db.entity.AgentScheduledJobEntity;
import me.taromati.almah.agent.db.entity.AgentSessionEntity;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.db.repository.AgentActivityLogRepository;
import me.taromati.almah.agent.db.repository.AgentMessageRepository;
import me.taromati.almah.agent.db.repository.AgentRoutineHistoryRepository;
import me.taromati.almah.agent.db.repository.AgentScheduledJobRepository;
import me.taromati.almah.agent.db.repository.AgentSessionRepository;
import me.taromati.almah.agent.service.AgentSessionService;
import me.taromati.almah.agent.mcp.McpClientManager;
import me.taromati.almah.agent.mcp.McpServerConfig;
import me.taromati.almah.agent.service.PersistentContextReader;
import me.taromati.almah.agent.service.SkillImporter;
import me.taromati.almah.agent.service.SkillManager;
import me.taromati.almah.agent.task.AuditLogService;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.core.response.RootResponse;
import me.taromati.almah.core.util.ConfigFileWriter;
import me.taromati.almah.core.util.LogFileReader;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/agent/api")
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentApiController {

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentScheduledJobRepository jobRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final AgentSessionService sessionService;
    private final AgentConfigProperties config;
    private final LlmClientResolver llmClientResolver;
    private final PersistentContextReader persistentContextReader;
    private final ToolRegistry toolRegistry;
    private final TaskStoreService taskStoreService;
    private final AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentRoutineHistoryRepository routineHistoryRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private McpClientManager mcpClientManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SkillManager skillManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SkillImporter skillImporter;

    // ─── Sessions ───

    @GetMapping("/sessions")
    public RootResponse<List<AgentSessionEntity>> listSessions() {
        return RootResponse.ok(sessionRepository.findAllByOrderByUpdatedAtDesc());
    }

    @GetMapping("/sessions/{id}/messages")
    public RootResponse<List<AgentMessageEntity>> getSessionMessages(@PathVariable("id") String id) {
        return RootResponse.ok(sessionService.getMessages(id));
    }

    @PostMapping("/sessions/{id}/reset")
    public RootResponse<Void> resetSession(@PathVariable("id") String id) {
        sessionService.resetSession(id);
        return RootResponse.ok();
    }

    @GetMapping("/sessions/{id}")
    public RootResponse<Map<String, Object>> getSession(@PathVariable("id") String id) {
        var session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        int messageCount = sessionService.getMessageCount(id);

        Map<String, Object> result = new HashMap<>();
        result.put("id", session.getId());
        result.put("channelId", session.getChannelId());
        result.put("title", session.getTitle());
        result.put("summary", session.getSummary());
        result.put("compactionCount", session.getCompactionCount());
        result.put("toolApprovals", session.getToolApprovals());
        result.put("llmModel", session.getLlmModel());
        result.put("llmProvider", config.getLlmProviderName());
        try {
            result.put("defaultModel", llmClientResolver.resolve(config.getLlmProviderName()).getDefaultModel());
        } catch (Exception ignored) { /* resolver 실패 시 무시 */ }
        result.put("active", session.getActive());
        result.put("createdAt", session.getCreatedAt());
        result.put("updatedAt", session.getUpdatedAt());
        result.put("messageCount", messageCount);
        return RootResponse.ok(result);
    }

    @DeleteMapping("/sessions/{id}")
    public RootResponse<Void> deleteSession(@PathVariable("id") String id) {
        sessionService.deleteSession(id);
        return RootResponse.ok();
    }

    @DeleteMapping("/sessions")
    public RootResponse<Void> deleteAllSessions() {
        sessionService.deleteAllSessions();
        return RootResponse.ok();
    }

    @PostMapping("/sessions/{id}/model")
    public RootResponse<Void> updateSessionModel(@PathVariable("id") String id,
                                                  @RequestBody Map<String, String> request) {
        String provider = request.get("provider");
        String model = request.get("model");
        if (provider != null && !provider.isBlank()) {
            // 프로바이더 유효성 검증
            llmClientResolver.resolve(provider);
            config.setLlmProviderName(provider);
            try {
                ConfigFileWriter.updateOrAddYamlValue("plugins.agent.llm-provider-name", provider);
            } catch (java.io.IOException e) {
                log.warn("[Agent] config.yml 업데이트 실패: {}", e.getMessage());
            }
        }
        sessionService.updateLlmModel(id, model != null && !model.isBlank() ? model : null);
        return RootResponse.ok();
    }

    @DeleteMapping("/messages/{id}")
    public RootResponse<Void> deleteMessage(@PathVariable("id") String id) {
        messageRepository.deleteById(id);
        return RootResponse.ok();
    }

    // ─── Tasks ───

    @GetMapping("/tasks")
    public RootResponse<List<AgentTaskItemEntity>> listTasks() {
        return RootResponse.ok(taskStoreService.findAll());
    }

    @PostMapping("/tasks")
    public RootResponse<AgentTaskItemEntity> createTask(@RequestBody Map<String, String> request) {
        String title = request.get("title");
        String description = request.get("description");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        return RootResponse.ok(taskStoreService.create(title, description, "web-ui"));
    }

    @PutMapping("/tasks/{id}/status")
    public RootResponse<AgentTaskItemEntity> updateTaskStatus(
            @PathVariable("id") String id,
            @RequestBody Map<String, String> request) {
        String status = request.get("status");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        return RootResponse.ok(taskStoreService.transition(id, status));
    }

    @DeleteMapping("/tasks/{id}")
    public RootResponse<Void> deleteTask(@PathVariable("id") String id) {
        taskStoreService.delete(id);
        return RootResponse.ok(null);
    }

    // ─── Audit Logs ───

    @GetMapping("/audit-logs")
    public RootResponse<List<AgentAuditLogEntity>> getAuditLogs(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<AgentAuditLogEntity> logs = auditLogService.findRecent();
        if (logs.size() > limit) {
            logs = logs.subList(0, limit);
        }
        return RootResponse.ok(logs);
    }

    @GetMapping("/audit-logs/{taskId}")
    public RootResponse<List<AgentAuditLogEntity>> getAuditLogsByTask(@PathVariable("taskId") String taskId) {
        return RootResponse.ok(auditLogService.findByTaskItem(taskId));
    }

    // ─── Scheduled Jobs ───

    @GetMapping("/jobs")
    public RootResponse<List<AgentScheduledJobEntity>> listJobs() {
        return RootResponse.ok(jobRepository.findAllByOrderByCreatedAtDesc());
    }

    @DeleteMapping("/jobs/{id}")
    public RootResponse<Void> deleteJob(@PathVariable("id") String id) {
        jobRepository.deleteById(id);
        return RootResponse.ok();
    }

    // ─── Activity Log ───

    @GetMapping("/activity-log")
    public RootResponse<Page<AgentActivityLogEntity>> getActivityLog(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "type", required = false) String type
    ) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AgentActivityLogEntity> result;
        boolean hasSearch = search != null && !search.isBlank();
        boolean hasType = type != null && !type.isBlank();

        if (hasType && hasSearch) {
            result = activityLogRepository.findByActivityTypeAndResultTextContainingOrActivityTypeAndJobNameContaining(
                    type, search, type, search, pageRequest);
        } else if (hasType) {
            result = activityLogRepository.findByActivityTypeOrderByCreatedAtDesc(type, pageRequest);
        } else if (hasSearch) {
            result = activityLogRepository.findByResultTextContainingOrJobNameContaining(
                    search, search, pageRequest);
        } else {
            result = activityLogRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        }
        return RootResponse.ok(result);
    }

    // ─── Routine History ───

    @GetMapping("/routine-history")
    public RootResponse<Page<AgentRoutineHistoryEntity>> getRoutineHistory(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        if (routineHistoryRepository == null) return RootResponse.ok(Page.empty());
        PageRequest pageRequest = PageRequest.of(page, size);
        return RootResponse.ok(routineHistoryRepository.findAllByOrderByCompletedAtDesc(pageRequest));
    }

    @DeleteMapping("/routine-history")
    public RootResponse<Void> deleteOldRoutineHistory(
            @RequestParam(name = "olderThanMonths", defaultValue = "3") int olderThanMonths
    ) {
        if (routineHistoryRepository == null) return RootResponse.ok();
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(olderThanMonths);
        routineHistoryRepository.deleteByCompletedAtBefore(cutoff);
        return RootResponse.ok();
    }

    // ─── Real-time Logs ───

    @GetMapping("/logs/recent")
    public RootResponse<List<String>> getRecentLogs(
            @RequestParam(name = "lines", defaultValue = "200") int lines) {
        return RootResponse.ok(LogFileReader.readRecent("agent", Math.min(lines, 1000)));
    }

    // ─── Persona ───

    @GetMapping("/persona/file")
    public RootResponse<Map<String, String>> getPersonaFile() {
        String content = persistentContextReader.readPersonaMd();
        return RootResponse.ok(Map.of("content", content != null ? content : ""));
    }

    @PutMapping("/persona/file")
    public RootResponse<Void> savePersonaFile(@RequestBody Map<String, String> request) {
        String content = request.getOrDefault("content", "");
        persistentContextReader.writePersonaMd(content);
        return RootResponse.ok();
    }

    // ─── GUIDE.md ───

    @GetMapping("/guide")
    public RootResponse<Map<String, String>> getGuideFile() {
        String content = persistentContextReader.readGuideMd();
        return RootResponse.ok(Map.of("content", content != null ? content : ""));
    }

    @PutMapping("/guide")
    public RootResponse<Void> saveGuideFile(@RequestBody Map<String, String> request) {
        String content = request.getOrDefault("content", "");
        persistentContextReader.writeGuideMd(content);
        return RootResponse.ok();
    }

    // ─── TOOLS.md ───

    @GetMapping("/tools-md")
    public RootResponse<Map<String, String>> getToolsMdFile() {
        String content = persistentContextReader.readToolsMd();
        return RootResponse.ok(Map.of("content", content != null ? content : ""));
    }

    @PutMapping("/tools-md")
    public RootResponse<Void> saveToolsMdFile(@RequestBody Map<String, String> request) {
        String content = request.getOrDefault("content", "");
        persistentContextReader.writeToolsMd(content);
        return RootResponse.ok();
    }

    // ─── USER.md ───

    @GetMapping("/user-md")
    public RootResponse<Map<String, String>> getUserMdFile() {
        String content = persistentContextReader.readUserMd();
        return RootResponse.ok(Map.of("content", content != null ? content : ""));
    }

    @PutMapping("/user-md")
    public RootResponse<Void> saveUserMdFile(@RequestBody Map<String, String> request) {
        String content = request.getOrDefault("content", "");
        persistentContextReader.writeUserMd(content);
        return RootResponse.ok();
    }

    // ─── Routine ───

    @PostMapping("/routine/run")
    public RootResponse<Map<String, String>> runRoutine() {
        // RoutineScheduler.runManual()은 Discord 리스너에서 호출 — Web API에서는 지원하지 않음
        return RootResponse.ok(Map.of("status", "info", "message", "Use !routine command in Discord"));
    }

    // ─── MCP Servers ───

    @GetMapping("/mcp/servers")
    public RootResponse<List<Map<String, Object>>> listMcpServers() {
        if (mcpClientManager == null) return RootResponse.ok(List.of());

        List<Map<String, Object>> result = new ArrayList<>();
        var detailedStatus = mcpClientManager.getDetailedStatus();
        for (var entry : mcpClientManager.getConfigs().entrySet()) {
            McpServerConfig cfg = entry.getValue();
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("name", cfg.name());
            server.put("transportType", cfg.transportType());
            server.put("command", cfg.command());
            server.put("args", cfg.args());
            server.put("url", cfg.url());
            server.put("env", cfg.env());
            server.put("headers", cfg.headers());
            server.put("timeoutSeconds", cfg.timeoutSeconds());
            server.put("maxRetries", cfg.maxRetries());
            server.put("autoConnect", cfg.autoConnect());
            server.put("enabled", cfg.enabled());
            server.put("defaultPolicy", cfg.defaultPolicy());
            server.put("toolPolicies", cfg.toolPolicies());
            server.put("state", mcpClientManager.getConnectionState(cfg.name()));
            server.put("error", mcpClientManager.getConnectionError(cfg.name()));
            server.put("connected", "CONNECTED".equals(mcpClientManager.getConnectionState(cfg.name())));
            server.put("toolCount", mcpClientManager.getServerTools(cfg.name()).size());
            server.put("tools", mcpClientManager.getServerToolsLocal(cfg.name()));
            var statusInfo = detailedStatus.get(cfg.name());
            if (statusInfo != null) {
                server.put("retryCount", statusInfo.get("retryCount"));
            }
            // 인증 알림
            var notifications = mcpClientManager.getServerAuthNotifications(cfg.name());
            if (!notifications.isEmpty()) {
                server.put("authNotifications", notifications);
            }
            result.add(server);
        }
        return RootResponse.ok(result);
    }

    @PostMapping("/mcp/servers")
    @SuppressWarnings("unchecked")
    public RootResponse<Map<String, String>> addMcpServer(@RequestBody Map<String, Object> request) {
        if (mcpClientManager == null) {
            return RootResponse.ok(Map.of("result", "MCP 미활성화"));
        }
        McpServerConfig cfg = parseMcpConfig(request);
        String result = mcpClientManager.addServer(cfg);
        return RootResponse.ok(Map.of("result", result));
    }

    @DeleteMapping("/mcp/servers")
    public RootResponse<Map<String, String>> removeMcpServer(@RequestParam("name") String name) {
        if (mcpClientManager == null) {
            return RootResponse.ok(Map.of("result", "MCP 미활성화"));
        }
        return RootResponse.ok(Map.of("result", mcpClientManager.removeServer(name)));
    }

    @PostMapping("/mcp/servers/connect")
    public RootResponse<Map<String, String>> connectMcpServer(@RequestParam("name") String name) {
        if (mcpClientManager == null) {
            return RootResponse.ok(Map.of("result", "MCP 미활성화"));
        }
        return RootResponse.ok(Map.of("result", mcpClientManager.reconnect(name)));
    }

    @PostMapping("/mcp/servers/disconnect")
    public RootResponse<Map<String, String>> disconnectMcpServer(@RequestParam("name") String name) {
        if (mcpClientManager == null) {
            return RootResponse.ok(Map.of("result", "MCP 미활성화"));
        }
        return RootResponse.ok(Map.of("result", mcpClientManager.disconnect(name)));
    }

    @PutMapping("/mcp/servers")
    @SuppressWarnings("unchecked")
    public RootResponse<Map<String, String>> updateMcpServer(@RequestBody Map<String, Object> request) {
        if (mcpClientManager == null) {
            return RootResponse.ok(Map.of("result", "MCP 미활성화"));
        }

        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            return RootResponse.ok(Map.of("result", "name is required"));
        }
        if (!mcpClientManager.getConfigs().containsKey(name)) {
            return RootResponse.ok(Map.of("result", "등록되지 않은 서버: " + name));
        }

        McpServerConfig cfg = parseMcpConfig(request);
        String result = mcpClientManager.updateConfig(cfg);
        return RootResponse.ok(Map.of("result", result));
    }

    @GetMapping("/mcp/servers/stderr")
    public RootResponse<List<String>> getMcpServerStderr(@RequestParam("name") String name) {
        if (mcpClientManager == null) return RootResponse.ok(List.of());
        return RootResponse.ok(mcpClientManager.getServerStderr(name));
    }

    @GetMapping("/mcp/servers/notifications")
    public RootResponse<List<Map<String, Object>>> getMcpNotifications(@RequestParam("name") String name) {
        if (mcpClientManager == null) return RootResponse.ok(List.of());
        return RootResponse.ok(mcpClientManager.getServerAuthNotifications(name));
    }

    @PostMapping("/mcp/servers/notifications/clear")
    public RootResponse<Void> clearMcpNotifications(@RequestParam("name") String name) {
        if (mcpClientManager != null) mcpClientManager.clearServerAuthNotifications(name);
        return RootResponse.ok();
    }

    @SuppressWarnings("unchecked")
    private McpServerConfig parseMcpConfig(Map<String, Object> request) {
        String name = (String) request.get("name");
        String transport = (String) request.getOrDefault("transportType", "stdio");
        String command = (String) request.get("command");
        List<String> args = request.containsKey("args") ? (List<String>) request.get("args") : List.of();
        String url = (String) request.get("url");
        boolean autoConnect = request.containsKey("autoConnect") ? (Boolean) request.get("autoConnect") : true;
        Map<String, String> env = request.containsKey("env") ? (Map<String, String>) request.get("env") : Map.of();
        Map<String, String> headers = request.containsKey("headers") ? (Map<String, String>) request.get("headers") : Map.of();
        int timeoutSeconds = request.containsKey("timeoutSeconds") ? ((Number) request.get("timeoutSeconds")).intValue() : 0;
        int maxRetries = request.containsKey("maxRetries") ? ((Number) request.get("maxRetries")).intValue() : 0;
        Map<String, String> toolPolicies = request.containsKey("toolPolicies") ? (Map<String, String>) request.get("toolPolicies") : Map.of();
        String defaultPolicy = request.containsKey("defaultPolicy") ? (String) request.get("defaultPolicy") : null;
        boolean enabled = request.containsKey("enabled") ? (Boolean) request.get("enabled") : true;

        String trustLevel = request.containsKey("trustLevel") ? (String) request.get("trustLevel") : null;

        return new McpServerConfig(
                name, transport, command, args, env, url, headers,
                autoConnect, timeoutSeconds, maxRetries,
                toolPolicies, defaultPolicy, trustLevel, enabled);
    }

    // ─── Skills ───

    @GetMapping("/skills")
    public RootResponse<List<Map<String, Object>>> listSkills() {
        if (skillManager == null) return RootResponse.ok(List.of());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var cached : skillManager.getAll()) {
            var skill = cached.skillFile();
            var gating = cached.gatingResult();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", skill.name());
            item.put("description", skill.description());
            item.put("active", skill.active());
            item.put("gatingStatus", gating.status().name());
            item.put("gatingReason", gating.reason());
            item.put("tools", skill.tools());
            item.put("mcpServer", skill.mcpServer());
            item.put("os", skill.os());
            result.add(item);
        }
        return RootResponse.ok(result);
    }

    @PostMapping("/skills/{name}/enable")
    public RootResponse<Map<String, String>> enableSkill(@PathVariable("name") String name) {
        if (skillManager == null) return RootResponse.ok(Map.of("result", "스킬 미활성화"));
        try {
            skillManager.enable(name);
            return RootResponse.ok(Map.of("result", "enabled"));
        } catch (Exception e) {
            return RootResponse.ok(Map.of("result", "실패: " + e.getMessage()));
        }
    }

    @PostMapping("/skills/{name}/disable")
    public RootResponse<Map<String, String>> disableSkill(@PathVariable("name") String name) {
        if (skillManager == null) return RootResponse.ok(Map.of("result", "스킬 미활성화"));
        try {
            skillManager.disable(name);
            return RootResponse.ok(Map.of("result", "disabled"));
        } catch (Exception e) {
            return RootResponse.ok(Map.of("result", "실패: " + e.getMessage()));
        }
    }

    @DeleteMapping("/skills/{name}")
    public RootResponse<Map<String, String>> removeSkill(@PathVariable("name") String name) {
        if (skillManager == null) return RootResponse.ok(Map.of("result", "스킬 미활성화"));
        try {
            skillManager.remove(name);
            return RootResponse.ok(Map.of("result", "removed"));
        } catch (Exception e) {
            return RootResponse.ok(Map.of("result", "실패: " + e.getMessage()));
        }
    }

    @PostMapping("/skills/install")
    public RootResponse<Map<String, String>> installSkill(@RequestBody Map<String, String> request) {
        if (skillImporter == null) return RootResponse.ok(Map.of("result", "스킬 미활성화"));
        String source = request.getOrDefault("source", "github");
        String url = request.get("url");
        if (url == null || url.isBlank()) return RootResponse.ok(Map.of("result", "url이 필요합니다"));
        try {
            String skillName = skillImporter.importSkill(source, url);
            return RootResponse.ok(Map.of("result", "installed", "name", skillName));
        } catch (Exception e) {
            return RootResponse.ok(Map.of("result", "실패: " + e.getMessage()));
        }
    }

    @GetMapping("/skills/{name}/content")
    public RootResponse<Map<String, String>> getSkillContent(@PathVariable("name") String name) {
        Path skillPath = resolveActualSkillPath(name);
        if (skillPath == null || !Files.exists(skillPath)) {
            return RootResponse.ok(Map.of("content", ""));
        }
        try {
            return RootResponse.ok(Map.of("content", Files.readString(skillPath)));
        } catch (IOException e) {
            return RootResponse.ok(Map.of("content", "", "error", e.getMessage()));
        }
    }

    @PutMapping("/skills/{name}/content")
    public RootResponse<Map<String, String>> updateSkillContent(
            @PathVariable("name") String name, @RequestBody Map<String, String> request) {
        if (skillManager == null) return RootResponse.ok(Map.of("result", "스킬 미활성화"));
        String content = request.get("content");
        if (content == null) return RootResponse.ok(Map.of("result", "content is required"));
        try {
            Path existing = resolveActualSkillPath(name);
            if (existing != null && Files.exists(existing)) {
                Files.writeString(existing, content);
                skillManager.loadAll();
            } else {
                skillManager.create(name, content);
            }
            return RootResponse.ok(Map.of("result", "saved"));
        } catch (Exception e) {
            return RootResponse.ok(Map.of("result", "실패: " + e.getMessage()));
        }
    }

    /**
     * frontmatter name으로 실제 SKILL.md 경로를 찾는다.
     * 디렉토리명과 frontmatter name이 다를 수 있으므로 fallback 스캔.
     */
    private Path resolveActualSkillPath(String name) {
        Path direct = persistentContextReader.resolveSkillPath(name);
        if (Files.exists(direct)) return direct;

        Path skillsDir = persistentContextReader.resolveSkillsDir();
        if (!Files.isDirectory(skillsDir)) return null;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(skillsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                Path md = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                var lines = Files.readAllLines(md);
                if (lines.isEmpty() || !lines.getFirst().trim().equals("---")) continue;
                for (int i = 1; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    if (trimmed.equals("---")) break;
                    if (trimmed.startsWith("name:")) {
                        String val = trimmed.substring(5).trim();
                        if ((val.startsWith("\"") && val.endsWith("\""))
                                || (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        if (val.equals(name)) return md;
                        break;
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    @GetMapping("/skills/search")
    public RootResponse<List<Map<String, Object>>> searchSkills(@RequestParam("q") String query) {
        if (skillImporter == null) return RootResponse.ok(List.of());
        try {
            return RootResponse.ok(skillImporter.searchClawHub(query));
        } catch (Exception e) {
            return RootResponse.ok(List.of());
        }
    }

    // ─── Config ───

    @GetMapping("/config")
    public RootResponse<Map<String, Object>> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("systemPrompt", config.getSystemPrompt());
        result.put("llmProvider", config.getLlmProviderName());
        result.put("maxContextMessages", config.getMaxContextMessages());
        result.put("maxTokens", config.getMaxTokens());
        result.put("temperature", config.getTemperature());
        result.put("topP", config.getTopP());
        result.put("minP", config.getMinP());
        result.put("frequencyPenalty", config.getFrequencyPenalty());
        result.put("repetitionPenalty", config.getRepetitionPenalty());

        result.put("availableProviders", llmClientResolver.getAvailableProviders());

        // Session
        Map<String, Object> session = new HashMap<>();
        session.put("contextWindow", config.getSession().getContextWindow());
        session.put("compactionRatio", config.getSession().getCompactionRatio());
        session.put("recentKeep", config.getSession().getRecentKeep());
        session.put("charsPerToken", config.getSession().getCharsPerToken());
        session.put("sessionIdleTimeoutMinutes", config.getSession().getSessionIdleTimeoutMinutes());
        session.put("taskIdleTimeoutMinutes", config.getSession().getTaskIdleTimeoutMinutes());
        session.put("maxInactiveSessions", config.getSession().getMaxInactiveSessions());
        result.put("session", session);

        // Tools
        Map<String, Object> tools = new HashMap<>();
        tools.put("policy", config.getTools().getPolicy());
        tools.put("policyDefault", config.getTools().getPolicyDefault());
        result.put("tools", tools);

        // Exec
        Map<String, Object> exec = new HashMap<>();
        exec.put("security", config.getExec().getSecurity());
        exec.put("timeoutSeconds", config.getExec().getTimeoutSeconds());
        exec.put("outputLimitKb", config.getExec().getOutputLimitKb());
        exec.put("allowlist", config.getExec().getAllowlist());
        exec.put("blockedPatterns", config.getExec().getBlockedPatterns());
        result.put("exec", exec);

        // File
        Map<String, Object> file = new HashMap<>();
        file.put("maxFileSizeKb", config.getFile().getMaxFileSizeKb());
        file.put("maxSearchResults", config.getFile().getMaxSearchResults());
        file.put("maxSearchDepth", config.getFile().getMaxSearchDepth());
        result.put("file", file);

        // WebSearch
        Map<String, Object> webSearch = new HashMap<>();
        webSearch.put("searxngUrl", config.getWebSearch().getSearxngUrl());
        result.put("webSearch", webSearch);

        // WebFetch
        Map<String, Object> webFetch = new HashMap<>();
        webFetch.put("maxContentLength", config.getWebFetch().getMaxContentLength());
        webFetch.put("timeoutSeconds", config.getWebFetch().getTimeoutSeconds());
        result.put("webFetch", webFetch);

        // Browser
        Map<String, Object> browser = new HashMap<>();
        browser.put("headless", config.getBrowser().getHeadless());
        browser.put("timeoutSeconds", config.getBrowser().getTimeoutSeconds());
        browser.put("maxContentLength", config.getBrowser().getMaxContentLength());
        browser.put("autoCloseMinutes", config.getBrowser().getAutoCloseMinutes());
        result.put("browser", browser);

        // Subagent
        Map<String, Object> subagent = new HashMap<>();
        subagent.put("maxConcurrent", config.getSubagent().getMaxConcurrent());
        subagent.put("timeoutSeconds", config.getSubagent().getTimeoutSeconds());
        subagent.put("excludedTools", config.getSubagent().getExcludedTools());
        result.put("subagent", subagent);

        // Cron
        Map<String, Object> cron = new HashMap<>();
        cron.put("checkIntervalMs", config.getCron().getCheckIntervalMs());
        cron.put("agentTurnTimeoutSeconds", config.getCron().getAgentTurnTimeoutSeconds());
        cron.put("excludedTools", config.getCron().getExcludedTools());
        result.put("cron", cron);

        // Routine
        Map<String, Object> routine = new HashMap<>();
        routine.put("enabled", config.getRoutine().getEnabled());
        routine.put("intervalMs", config.getRoutine().getIntervalMs());
        routine.put("activeStartHour", config.getRoutine().getActiveStartHour());
        routine.put("activeEndHour", config.getRoutine().getActiveEndHour());
        routine.put("activeWorkMinutes", config.getRoutine().getActiveWorkMinutes());
        routine.put("excludedTools", config.getRoutine().getExcludedTools());
        result.put("routine", routine);

        // Suggest
        Map<String, Object> suggest = new HashMap<>();
        suggest.put("enabled", config.getSuggest().getEnabled());
        suggest.put("cooldownHours", config.getSuggest().getCooldownHours());
        suggest.put("dailyLimit", config.getSuggest().getDailyLimit());
        suggest.put("activeStartHour", config.getSuggest().getActiveStartHour());
        suggest.put("activeEndHour", config.getSuggest().getActiveEndHour());
        result.put("suggest", suggest);

        // Task
        Map<String, Object> taskConfig = new HashMap<>();
        taskConfig.put("maxRetry", config.getTask().getMaxRetry());
        taskConfig.put("approvalTimeoutHours", config.getTask().getApprovalTimeoutHours());
        taskConfig.put("reminderCount", config.getTask().getReminderCount());
        result.put("task", taskConfig);

        // Registered tool names
        result.put("registeredTools", toolRegistry.getRegisteredToolNames());

        // Provider capabilities
        Map<String, Object> providerCaps = new LinkedHashMap<>();
        for (String name : llmClientResolver.getAvailableProviders()) {
            try {
                var caps = llmClientResolver.resolve(name).getCapabilities();
                providerCaps.put(name, Map.of(
                        "contextWindow", caps.contextWindow() != null ? caps.contextWindow() : 32768,
                        "maxTokens", caps.maxTokens() != null ? caps.maxTokens() : 4096,
                        "charsPerToken", caps.charsPerToken() != null ? caps.charsPerToken() : 3,
                        "recentKeep", caps.recentKeep() != null ? caps.recentKeep() : 15
                ));
            } catch (Exception ignored) {}
        }
        result.put("providerCapabilities", providerCaps);

        return RootResponse.ok(result);
    }

    @PostMapping("/config")
    public RootResponse<Void> updateConfig(@RequestBody Map<String, Object> request) {
        List<ConfigFileWriter.PathValue> updates = new ArrayList<>();
        List<String> removeKeys = new ArrayList<>();

        // System Prompt
        if (request.containsKey("systemPrompt")) {
            String val = (String) request.get("systemPrompt");
            config.setSystemPrompt(val);
            updates.add(new ConfigFileWriter.PathValue("plugins.agent.system-prompt", val));
        }

        // LLM Provider
        if (request.containsKey("llmProvider")) {
            String val = (String) request.get("llmProvider");
            config.setLlmProviderName(val);
            updates.add(new ConfigFileWriter.PathValue("plugins.agent.llm-provider-name", val));
        }

        // Numeric fields
        if (request.containsKey("maxContextMessages")) {
            int val = ((Number) request.get("maxContextMessages")).intValue();
            config.setMaxContextMessages(val);
            updates.add(new ConfigFileWriter.PathValue("plugins.agent.max-context-messages", String.valueOf(val)));
        }
        // maxTokens — nullable (빈 값이면 프로바이더 기본값 사용)
        if (request.containsKey("maxTokens")) {
            Object val = request.get("maxTokens");
            if (val != null) {
                int v = ((Number) val).intValue();
                config.setMaxTokens(v);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.max-tokens", String.valueOf(v)));
            } else {
                config.setMaxTokens(null);
                removeKeys.add("plugins.agent.max-tokens");
            }
        }
        if (request.containsKey("temperature")) {
            double val = ((Number) request.get("temperature")).doubleValue();
            config.setTemperature(val);
            updates.add(new ConfigFileWriter.PathValue("plugins.agent.temperature", String.valueOf(val)));
        }

        // Nullable doubles
        updateNullableDouble(request, "topP", config::setTopP, "plugins.agent.top-p", updates, removeKeys);
        updateNullableDouble(request, "minP", config::setMinP, "plugins.agent.min-p", updates, removeKeys);
        updateNullableDouble(request, "frequencyPenalty", config::setFrequencyPenalty, "plugins.agent.frequency-penalty", updates, removeKeys);
        updateNullableDouble(request, "repetitionPenalty", config::setRepetitionPenalty, "plugins.agent.repetition-penalty", updates, removeKeys);

        // Session — contextWindow, recentKeep, charsPerToken은 nullable (프로바이더 기본값 사용)
        if (request.containsKey("session") && request.get("session") instanceof Map<?, ?> sessionMap) {
            updateNullableInt(sessionMap, "contextWindow", config.getSession()::setContextWindow,
                    "plugins.agent.session.context-window", updates, removeKeys);
            if (sessionMap.containsKey("compactionRatio")) {
                Object val = sessionMap.get("compactionRatio");
                if (val != null) {
                    double v = ((Number) val).doubleValue();
                    config.getSession().setCompactionRatio(v);
                    updates.add(new ConfigFileWriter.PathValue("plugins.agent.session.compaction-ratio", String.valueOf(v)));
                }
            }
            updateNullableInt(sessionMap, "recentKeep", config.getSession()::setRecentKeep,
                    "plugins.agent.session.recent-keep", updates, removeKeys);
            updateNullableInt(sessionMap, "charsPerToken", config.getSession()::setCharsPerToken,
                    "plugins.agent.session.chars-per-token", updates, removeKeys);
            if (sessionMap.containsKey("sessionIdleTimeoutMinutes") && sessionMap.get("sessionIdleTimeoutMinutes") != null) {
                int val = ((Number) sessionMap.get("sessionIdleTimeoutMinutes")).intValue();
                config.getSession().setSessionIdleTimeoutMinutes(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.session.session-idle-timeout-minutes", String.valueOf(val)));
            }
            if (sessionMap.containsKey("taskIdleTimeoutMinutes") && sessionMap.get("taskIdleTimeoutMinutes") != null) {
                int val = ((Number) sessionMap.get("taskIdleTimeoutMinutes")).intValue();
                config.getSession().setTaskIdleTimeoutMinutes(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.session.task-idle-timeout-minutes", String.valueOf(val)));
            }
            if (sessionMap.containsKey("maxInactiveSessions") && sessionMap.get("maxInactiveSessions") != null) {
                int val = ((Number) sessionMap.get("maxInactiveSessions")).intValue();
                config.getSession().setMaxInactiveSessions(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.session.max-inactive-sessions", String.valueOf(val)));
            }
        }

        // Exec
        if (request.containsKey("exec") && request.get("exec") instanceof Map<?, ?> execMap) {
            if (execMap.containsKey("security")) {
                String val = (String) execMap.get("security");
                config.getExec().setSecurity(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.exec.security", val));
            }
            if (execMap.containsKey("timeoutSeconds") && execMap.get("timeoutSeconds") != null) {
                int val = ((Number) execMap.get("timeoutSeconds")).intValue();
                config.getExec().setTimeoutSeconds(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.exec.timeout-seconds", String.valueOf(val)));
            }
            if (execMap.containsKey("outputLimitKb") && execMap.get("outputLimitKb") != null) {
                int val = ((Number) execMap.get("outputLimitKb")).intValue();
                config.getExec().setOutputLimitKb(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.exec.output-limit-kb", String.valueOf(val)));
            }
            updateListField(execMap, "allowlist", config.getExec()::setAllowlist, "plugins.agent.exec.allowlist");
            updateListField(execMap, "blockedPatterns", config.getExec()::setBlockedPatterns, "plugins.agent.exec.blocked-patterns");
        }

        // File
        if (request.containsKey("file") && request.get("file") instanceof Map<?, ?> fileMap) {
            if (fileMap.containsKey("maxFileSizeKb") && fileMap.get("maxFileSizeKb") != null) {
                int val = ((Number) fileMap.get("maxFileSizeKb")).intValue();
                config.getFile().setMaxFileSizeKb(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.file.max-file-size-kb", String.valueOf(val)));
            }
            if (fileMap.containsKey("maxSearchResults") && fileMap.get("maxSearchResults") != null) {
                int val = ((Number) fileMap.get("maxSearchResults")).intValue();
                config.getFile().setMaxSearchResults(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.file.max-search-results", String.valueOf(val)));
            }
            if (fileMap.containsKey("maxSearchDepth") && fileMap.get("maxSearchDepth") != null) {
                int val = ((Number) fileMap.get("maxSearchDepth")).intValue();
                config.getFile().setMaxSearchDepth(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.file.max-search-depth", String.valueOf(val)));
            }
        }

        // WebSearch
        if (request.containsKey("webSearch") && request.get("webSearch") instanceof Map<?, ?> wsMap) {
            if (wsMap.containsKey("searxngUrl")) {
                String val = wsMap.get("searxngUrl") != null ? (String) wsMap.get("searxngUrl") : "";
                config.getWebSearch().setSearxngUrl(val.isEmpty() ? null : val);
                if (val.isEmpty()) {
                    removeKeys.add("plugins.agent.web-search.searxng-url");
                } else {
                    updates.add(new ConfigFileWriter.PathValue("plugins.agent.web-search.searxng-url", val));
                }
            }
        }

        // WebFetch
        if (request.containsKey("webFetch") && request.get("webFetch") instanceof Map<?, ?> wfMap) {
            if (wfMap.containsKey("maxContentLength") && wfMap.get("maxContentLength") != null) {
                int val = ((Number) wfMap.get("maxContentLength")).intValue();
                config.getWebFetch().setMaxContentLength(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.web-fetch.max-content-length", String.valueOf(val)));
            }
            if (wfMap.containsKey("timeoutSeconds") && wfMap.get("timeoutSeconds") != null) {
                int val = ((Number) wfMap.get("timeoutSeconds")).intValue();
                config.getWebFetch().setTimeoutSeconds(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.web-fetch.timeout-seconds", String.valueOf(val)));
            }
        }

        // Browser
        if (request.containsKey("browser") && request.get("browser") instanceof Map<?, ?> brMap) {
            if (brMap.containsKey("headless") && brMap.get("headless") != null) {
                boolean val = (Boolean) brMap.get("headless");
                config.getBrowser().setHeadless(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.browser.headless", String.valueOf(val)));
            }
            if (brMap.containsKey("timeoutSeconds") && brMap.get("timeoutSeconds") != null) {
                int val = ((Number) brMap.get("timeoutSeconds")).intValue();
                config.getBrowser().setTimeoutSeconds(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.browser.timeout-seconds", String.valueOf(val)));
            }
            if (brMap.containsKey("maxContentLength") && brMap.get("maxContentLength") != null) {
                int val = ((Number) brMap.get("maxContentLength")).intValue();
                config.getBrowser().setMaxContentLength(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.browser.max-content-length", String.valueOf(val)));
            }
            if (brMap.containsKey("autoCloseMinutes") && brMap.get("autoCloseMinutes") != null) {
                int val = ((Number) brMap.get("autoCloseMinutes")).intValue();
                config.getBrowser().setAutoCloseMinutes(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.browser.auto-close-minutes", String.valueOf(val)));
            }
        }

        // Subagent
        if (request.containsKey("subagent") && request.get("subagent") instanceof Map<?, ?> saMap) {
            if (saMap.containsKey("maxConcurrent") && saMap.get("maxConcurrent") != null) {
                int val = ((Number) saMap.get("maxConcurrent")).intValue();
                config.getSubagent().setMaxConcurrent(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.subagent.max-concurrent", String.valueOf(val)));
            }
            if (saMap.containsKey("timeoutSeconds") && saMap.get("timeoutSeconds") != null) {
                int val = ((Number) saMap.get("timeoutSeconds")).intValue();
                config.getSubagent().setTimeoutSeconds(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.subagent.timeout-seconds", String.valueOf(val)));
            }
            updateListField(saMap, "excludedTools", config.getSubagent()::setExcludedTools, "plugins.agent.subagent.excluded-tools");
        }

        // Cron
        if (request.containsKey("cron") && request.get("cron") instanceof Map<?, ?> cronMap) {
            if (cronMap.containsKey("checkIntervalMs") && cronMap.get("checkIntervalMs") != null) {
                long val = ((Number) cronMap.get("checkIntervalMs")).longValue();
                config.getCron().setCheckIntervalMs(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.cron.check-interval-ms", String.valueOf(val)));
            }
            if (cronMap.containsKey("agentTurnTimeoutSeconds") && cronMap.get("agentTurnTimeoutSeconds") != null) {
                int val = ((Number) cronMap.get("agentTurnTimeoutSeconds")).intValue();
                config.getCron().setAgentTurnTimeoutSeconds(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.cron.agent-turn-timeout-seconds", String.valueOf(val)));
            }
            updateListField(cronMap, "excludedTools", config.getCron()::setExcludedTools, "plugins.agent.cron.excluded-tools");
        }

        // Routine
        if (request.containsKey("routine") && request.get("routine") instanceof Map<?, ?> rtMap) {
            if (rtMap.containsKey("enabled")) {
                boolean val = (Boolean) rtMap.get("enabled");
                config.getRoutine().setEnabled(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.routine.enabled", String.valueOf(val)));
            }
            if (rtMap.containsKey("intervalMs")) {
                long val = ((Number) rtMap.get("intervalMs")).longValue();
                config.getRoutine().setIntervalMs(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.routine.interval-ms", String.valueOf(val)));
            }
            if (rtMap.containsKey("activeStartHour")) {
                int val = ((Number) rtMap.get("activeStartHour")).intValue();
                config.getRoutine().setActiveStartHour(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.routine.active-start-hour", String.valueOf(val)));
            }
            if (rtMap.containsKey("activeEndHour")) {
                int val = ((Number) rtMap.get("activeEndHour")).intValue();
                config.getRoutine().setActiveEndHour(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.routine.active-end-hour", String.valueOf(val)));
            }
            if (rtMap.containsKey("activeWorkMinutes")) {
                int val = ((Number) rtMap.get("activeWorkMinutes")).intValue();
                config.getRoutine().setActiveWorkMinutes(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.routine.active-work-minutes", String.valueOf(val)));
            }
            updateListField(rtMap, "excludedTools", config.getRoutine()::setExcludedTools, "plugins.agent.routine.excluded-tools");
        }

        // Suggest
        if (request.containsKey("suggest") && request.get("suggest") instanceof Map<?, ?> sgMap) {
            if (sgMap.containsKey("enabled")) {
                boolean val = (Boolean) sgMap.get("enabled");
                config.getSuggest().setEnabled(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.suggest.enabled", String.valueOf(val)));
            }
            if (sgMap.containsKey("cooldownHours") && sgMap.get("cooldownHours") != null) {
                int val = ((Number) sgMap.get("cooldownHours")).intValue();
                config.getSuggest().setCooldownHours(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.suggest.cooldown-hours", String.valueOf(val)));
            }
            if (sgMap.containsKey("dailyLimit") && sgMap.get("dailyLimit") != null) {
                int val = ((Number) sgMap.get("dailyLimit")).intValue();
                config.getSuggest().setDailyLimit(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.suggest.daily-limit", String.valueOf(val)));
            }
            if (sgMap.containsKey("activeStartHour") && sgMap.get("activeStartHour") != null) {
                int val = ((Number) sgMap.get("activeStartHour")).intValue();
                config.getSuggest().setActiveStartHour(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.suggest.active-start-hour", String.valueOf(val)));
            }
            if (sgMap.containsKey("activeEndHour") && sgMap.get("activeEndHour") != null) {
                int val = ((Number) sgMap.get("activeEndHour")).intValue();
                config.getSuggest().setActiveEndHour(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.suggest.active-end-hour", String.valueOf(val)));
            }
        }

        // Task
        if (request.containsKey("task") && request.get("task") instanceof Map<?, ?> tdMap) {
            if (tdMap.containsKey("maxRetry") && tdMap.get("maxRetry") != null) {
                int val = ((Number) tdMap.get("maxRetry")).intValue();
                config.getTask().setMaxRetry(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.task.max-retry", String.valueOf(val)));
            }
            if (tdMap.containsKey("approvalTimeoutHours") && tdMap.get("approvalTimeoutHours") != null) {
                int val = ((Number) tdMap.get("approvalTimeoutHours")).intValue();
                config.getTask().setApprovalTimeoutHours(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.task.approval-timeout-hours", String.valueOf(val)));
            }
            if (tdMap.containsKey("reminderCount") && tdMap.get("reminderCount") != null) {
                int val = ((Number) tdMap.get("reminderCount")).intValue();
                config.getTask().setReminderCount(val);
                updates.add(new ConfigFileWriter.PathValue("plugins.agent.task.reminder-count", String.valueOf(val)));
            }
        }

        // Tool Policy — per-tool policy map
        if (request.containsKey("toolsPolicy") && request.get("toolsPolicy") instanceof Map<?, ?> policyMap) {
            @SuppressWarnings("unchecked")
            Map<String, String> newPolicy = (Map<String, String>) policyMap;
            Map<String, String> oldPolicy = config.getTools().getPolicy();

            // 추가/변경
            for (var entry : newPolicy.entrySet()) {
                String tool = entry.getKey();
                String policy = entry.getValue();
                if (!policy.equals(oldPolicy.get(tool))) {
                    try {
                        ConfigFileWriter.updateOrAddYamlValue("plugins.agent.tools.policy." + tool, policy);
                    } catch (Exception e) {
                        log.warn("[Agent] Failed to persist tools.policy.{}: {}", tool, e.getMessage());
                    }
                }
            }
            // 제거 (이전에 있었지만 새 맵에 없는 키)
            for (String tool : oldPolicy.keySet()) {
                if (!newPolicy.containsKey(tool)) {
                    removeKeys.add("plugins.agent.tools.policy." + tool);
                }
            }
            config.getTools().setPolicy(new LinkedHashMap<>(newPolicy));
        }

        // Tool Policy — policyDefault
        if (request.containsKey("toolsPolicyDefault")) {
            String val = (String) request.get("toolsPolicyDefault");
            config.getTools().setPolicyDefault(val);
            updates.add(new ConfigFileWriter.PathValue("plugins.agent.tools.policy-default", val));
        }

        // Persist all PathValue updates
        if (!updates.isEmpty()) {
            try {
                ConfigFileWriter.updateYamlValues(updates);
                log.info("[Agent] Config saved to config.yml ({} updates)", updates.size());
            } catch (Exception e) {
                log.warn("[Agent] Config updated in memory, but failed to save to config.yml: {}", e.getMessage());
            }
        }

        // Remove nullable keys from config.yml
        for (String key : removeKeys) {
            try {
                ConfigFileWriter.removeYamlKey(key);
            } catch (Exception e) {
                log.warn("[Agent] Failed to remove key {}: {}", key, e.getMessage());
            }
        }

        return RootResponse.ok();
    }

    private void updateNullableDouble(Map<String, Object> request, String key,
                                       java.util.function.Consumer<Double> setter,
                                       String yamlPath, List<ConfigFileWriter.PathValue> updates,
                                       List<String> removeKeys) {
        if (request.containsKey(key)) {
            Object val = request.get(key);
            if (val != null) {
                double d = ((Number) val).doubleValue();
                setter.accept(d);
                updates.add(new ConfigFileWriter.PathValue(yamlPath, String.valueOf(d)));
            } else {
                setter.accept(null);
                removeKeys.add(yamlPath);
            }
        }
    }

    private void updateNullableInt(Map<?, ?> map, String key,
                                    java.util.function.Consumer<Integer> setter,
                                    String yamlPath,
                                    List<ConfigFileWriter.PathValue> updates,
                                    List<String> removeKeys) {
        if (map.containsKey(key)) {
            Object val = map.get(key);
            if (val != null) {
                int v = ((Number) val).intValue();
                setter.accept(v);
                updates.add(new ConfigFileWriter.PathValue(yamlPath, String.valueOf(v)));
            } else {
                setter.accept(null);
                removeKeys.add(yamlPath);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateListField(Map<?, ?> map, String key,
                                  java.util.function.Consumer<List<String>> setter,
                                  String yamlPath) {
        if (map.containsKey(key) && map.get(key) instanceof List<?> list) {
            List<String> values = (List<String>) list;
            setter.accept(new ArrayList<>(values));
            try {
                ConfigFileWriter.updateYamlList(yamlPath, values);
            } catch (Exception e) {
                log.warn("[Agent] Failed to persist {}: {}", yamlPath, e.getMessage());
            }
        }
    }
}
