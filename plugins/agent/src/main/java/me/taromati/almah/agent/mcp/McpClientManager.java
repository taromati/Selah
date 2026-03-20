package me.taromati.almah.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.llm.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP 클라이언트 매니저.
 * McpConnection에 연결 로직을 위임하고, coordination + 영속화만 담당.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class McpClientManager {

    /**
     * MCP 연결 상태 변경 리스너.
     */
    public interface McpEventListener {
        void onConnectionChanged(String serverName, boolean connected);
    }

    private static final long REFRESH_INTERVAL_MS = 5_000;

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, Long> configLastModified = new ConcurrentHashMap<>();
    private volatile long lastRefreshTime;
    private final List<McpEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ToolRegistry toolRegistry;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpClientManager(ToolRegistry toolRegistry, AgentConfigProperties config) {
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    // ── 생명주기 ──

    @PostConstruct
    void init() {
        loadConfigs();

        List<McpConnection> autoList = connections.values().stream()
                .filter(c -> c.getConfig().enabled() && c.getConfig().autoConnect())
                .toList();
        if (!autoList.isEmpty()) {
            log.info("[McpClientManager] Auto-connecting {} server(s) in background", autoList.size());
            CompletableFuture.runAsync(() -> {
                for (McpConnection conn : autoList) {
                    try {
                        String result = conn.connectSync();
                        log.info("[McpClientManager] Auto-connect '{}': {}", conn.getConfig().name(), result);
                    } catch (Throwable t) {
                        log.error("[McpClientManager] Auto-connect '{}' 예외: {}", conn.getConfig().name(), t.getMessage(), t);
                    }
                }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        connections.values().forEach(conn -> {
            try {
                conn.disconnect();
            } catch (Exception e) {
                log.warn("[McpClientManager] Error closing: {}", e.getMessage());
            }
        });
    }

    // ── Listener API ──

    public void addListener(McpEventListener listener) {
        listeners.add(listener);
    }

    void notifyListeners(String serverName, boolean connected) {
        for (McpEventListener listener : listeners) {
            try {
                listener.onConnectionChanged(serverName, connected);
            } catch (Exception e) {
                log.warn("[McpClientManager] Listener error for '{}': {}", serverName, e.getMessage());
            }
        }
    }

    // ── Public API ──

    public String connect(McpServerConfig cfg) {
        if (!cfg.enabled()) {
            return "서버가 비활성화 상태: " + cfg.name();
        }
        McpConnection existing = connections.remove(cfg.name());
        if (existing != null) {
            existing.disconnect();
            notifyListeners(cfg.name(), false);
        }
        McpConnection conn = new McpConnection(cfg, toolRegistry, objectMapper, this);
        connections.put(cfg.name(), conn);
        return conn.connectAsync() + ": " + cfg.name();
    }

    public String reconnect(String name) {
        McpServerConfig diskConfig = loadConfigFromDisk(name);
        if (diskConfig != null) return connect(diskConfig);
        McpConnection conn = connections.get(name);
        if (conn == null) return "등록되지 않은 서버: " + name;
        return conn.connectAsync() + ": " + name;
    }

    public String disconnect(String name) {
        McpConnection conn = connections.get(name);
        if (conn == null) return "등록되지 않은 서버: " + name;
        String result = conn.disconnect() + ": " + name;
        notifyListeners(name, false);
        return result;
    }

    public String addServer(McpServerConfig cfg) {
        saveConfig(cfg);
        return connect(cfg);
    }

    public String removeServer(String name) {
        McpConnection conn = connections.remove(name);
        configLastModified.remove(name);
        if (conn != null) {
            conn.disconnect();
            notifyListeners(name, false);
        }
        deleteConfigFile(name);
        return "서버 제거: " + name;
    }

    /**
     * Config 핫리로드 — 변경된 필드에 따라 재연결 여부 자동 결정.
     */
    public String updateConfig(McpServerConfig newCfg) {
        McpConnection conn = connections.get(newCfg.name());
        if (conn == null) return "등록되지 않은 서버: " + newCfg.name();

        saveConfig(newCfg);
        return applyConfigChange(conn, newCfg);
    }

    /**
     * 설정 변경 적용 — 핫/콜드 필드 판정 후 재연결 또는 핫 업데이트.
     * updateConfig() 전용 (사용자 명시적 요청이므로 reconnect 허용).
     */
    private String applyConfigChange(McpConnection conn, McpServerConfig newCfg) {
        McpServerConfig oldCfg = conn.getConfig();

        // enabled 변경 처리
        if (oldCfg.enabled() && !newCfg.enabled()) {
            conn.disconnect();
            notifyListeners(newCfg.name(), false);
            conn.updateHotConfig(newCfg);
            return "서버 비활성화: " + newCfg.name();
        }
        if (!oldCfg.enabled() && newCfg.enabled()) {
            return connect(newCfg);
        }

        boolean needsReconnect = !Objects.equals(oldCfg.command(), newCfg.command())
                || !Objects.equals(oldCfg.args(), newCfg.args())
                || !Objects.equals(oldCfg.env(), newCfg.env())
                || !Objects.equals(oldCfg.url(), newCfg.url())
                || !Objects.equals(oldCfg.headers(), newCfg.headers())
                || !Objects.equals(oldCfg.transportType(), newCfg.transportType());

        if (needsReconnect && newCfg.enabled()) {
            return connect(newCfg);
        } else {
            conn.updateHotConfig(newCfg);
            return "설정 업데이트 (재연결 불필요): " + newCfg.name();
        }
    }

    // ── 상태 조회 ──

    public Map<String, Map<String, Object>> getDetailedStatus() {
        refreshStaleConfigs();
        Map<String, Map<String, Object>> status = new LinkedHashMap<>();
        for (var entry : connections.entrySet()) {
            McpConnection conn = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("state", conn.getState().name());
            info.put("toolCount", conn.getToolNames().size());
            info.put("retryCount", conn.getRetryCount());
            info.put("maxRetries", conn.getConfig().maxRetries());
            info.put("enabled", conn.getConfig().enabled());
            info.put("defaultPolicy", conn.getConfig().defaultPolicy());
            info.put("toolPolicies", conn.getConfig().toolPolicies());
            if (conn.getLastError() != null) {
                info.put("error", conn.getLastError());
            }
            status.put(entry.getKey(), info);
        }
        return status;
    }

    public String getConnectionState(String name) {
        McpConnection conn = connections.get(name);
        return conn != null ? conn.getState().name() : "IDLE";
    }

    public String getConnectionError(String name) {
        McpConnection conn = connections.get(name);
        return conn != null ? conn.getLastError() : null;
    }

    public List<String> getServerStderr(String name) {
        McpConnection conn = connections.get(name);
        return conn != null ? conn.getStderrLog() : List.of();
    }

    public List<Map<String, Object>> getServerAuthNotifications(String name) {
        McpConnection conn = connections.get(name);
        if (conn == null) return List.of();
        return conn.getAuthNotifications().stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", n.timestamp().toString());
            m.put("message", n.message());
            m.put("url", n.url());
            m.put("code", n.code());
            return m;
        }).toList();
    }

    public void clearServerAuthNotifications(String name) {
        McpConnection conn = connections.get(name);
        if (conn != null) conn.clearAuthNotifications();
    }

    // ── Per-tool policy ──

    /**
     * namespaced 도구 이름으로 MCP 정책 조회.
     * 역방향 매핑(namespaced → local)을 사용하여 서버명 파싱 없이 정확한 조회.
     *
     * @return toolPolicies → 서버 defaultPolicy → null 순
     */
    public String getToolPolicyByNamespacedName(String namespacedName) {
        refreshStaleConfigs();
        for (var entry : connections.entrySet()) {
            McpConnection conn = entry.getValue();
            String localToolName = conn.getLocalToolName(namespacedName);
            if (localToolName != null) {
                McpServerConfig cfg = conn.getConfig();
                String policy = cfg.toolPolicies().get(localToolName);
                return policy != null ? policy : cfg.defaultPolicy();
            }
        }
        return null;
    }

    /**
     * namespaced 도구 이름으로 해당 MCP 서버의 trustLevel 조회.
     *
     * @return trustLevel 문자열 또는 null (MCP 도구가 아니거나 미설정)
     */
    public String getTrustLevelByNamespacedName(String namespacedName) {
        refreshStaleConfigs();
        for (var entry : connections.entrySet()) {
            McpConnection conn = entry.getValue();
            String localToolName = conn.getLocalToolName(namespacedName);
            if (localToolName != null) {
                return conn.getConfig().trustLevel();
            }
        }
        return null;
    }

    // ── 기존 API 호환 ──

    public Map<String, String> getStatus() {
        refreshStaleConfigs();
        Map<String, String> status = new LinkedHashMap<>();
        for (var entry : connections.entrySet()) {
            McpConnection conn = entry.getValue();
            int toolCount = conn.getToolNames().size();
            String label = switch (conn.getState()) {
                case CONNECTED -> "연결됨 (" + toolCount + "개 도구)";
                case CONNECTING -> "연결 중...";
                case RECONNECTING -> "재연결 중... (" + conn.getRetryCount() + "/" + conn.getConfig().maxRetries() + ")";
                case FAILED -> "연결 실패" + (conn.getLastError() != null ? ": " + conn.getLastError() : "");
                case IDLE -> "연결 안 됨";
            };
            status.put(entry.getKey(), label);
        }
        return status;
    }

    public List<String> getServerTools(String name) {
        McpConnection conn = connections.get(name);
        return conn != null ? conn.getToolNames() : List.of();
    }

    public List<String> getServerToolsLocal(String name) {
        McpConnection conn = connections.get(name);
        return conn != null ? conn.getLocalToolNames() : List.of();
    }

    public Map<String, McpServerConfig> getConfigs() {
        refreshStaleConfigs();
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        for (var entry : connections.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getConfig());
        }
        return Collections.unmodifiableMap(result);
    }

    public boolean isConnected(String name) {
        McpConnection conn = connections.get(name);
        return conn != null && conn.getState() == McpConnection.State.CONNECTED;
    }

    // ── Private ──

    /**
     * Config 파일 변경 감지 — 스킬의 refreshStaleEntries() 패턴.
     * 기존 연결의 config 변경, 새 config 파일 추가, config 파일 삭제를 감지.
     *
     * 안전성 보장:
     * - 5초 쓰로틀링으로 빈번한 호출 방지
     * - 키 스냅샷으로 순회 중 ConcurrentModificationException 방지
     * - 기존 연결은 hot config만 적용 (reconnect 없음 → registeredToolMap 유지)
     */
    private void refreshStaleConfigs() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_INTERVAL_MS) return;
        lastRefreshTime = now;

        Path mcpDir = resolveMcpDir();

        // 1. 기존 연결: 키 스냅샷으로 안전한 순회
        for (String name : List.copyOf(connections.keySet())) {
            Path file = mcpDir.resolve(configFileName(name));
            long currentMod = getLastModified(file);
            Long cachedMod = configLastModified.get(name);

            if (cachedMod == null) continue;

            if (currentMod == 0) {
                // 파일 삭제됨 — 경고만 (사용자가 removeServer로 제거)
                log.warn("[McpClientManager] Config file missing for '{}', 수동 제거 필요", name);
                configLastModified.put(name, 0L);
                continue;
            }

            if (currentMod == cachedMod) continue;

            McpServerConfig newCfg = loadConfigFromDisk(name);
            if (newCfg == null) continue;

            McpConnection conn = connections.get(name);
            if (conn == null) continue;

            // hot config만 적용, cold 필드 변경 시 경고 로그
            McpServerConfig oldCfg = conn.getConfig();
            boolean coldChanged = !Objects.equals(oldCfg.command(), newCfg.command())
                    || !Objects.equals(oldCfg.args(), newCfg.args())
                    || !Objects.equals(oldCfg.env(), newCfg.env())
                    || !Objects.equals(oldCfg.url(), newCfg.url())
                    || !Objects.equals(oldCfg.headers(), newCfg.headers())
                    || !Objects.equals(oldCfg.transportType(), newCfg.transportType());

            if (coldChanged) {
                log.warn("[McpClientManager] Config cold fields changed for '{}', 재연결 필요 (수동 reconnect)", name);
            }
            conn.updateHotConfig(newCfg);
            configLastModified.put(name, currentMod);
            log.info("[McpClientManager] Hot config reloaded for '{}'", name);
        }

        // 2. 새 config 파일 감지
        if (!Files.isDirectory(mcpDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mcpDir, "*.json")) {
            for (Path file : stream) {
                try {
                    McpServerConfig cfg = objectMapper.readValue(file.toFile(), McpServerConfig.class);
                    if (!connections.containsKey(cfg.name())) {
                        log.info("[McpClientManager] New config file detected: '{}'", cfg.name());
                        McpConnection conn = new McpConnection(cfg, toolRegistry, objectMapper, this);
                        connections.put(cfg.name(), conn);
                        configLastModified.put(cfg.name(), getLastModified(file));
                        if (cfg.enabled() && cfg.autoConnect()) {
                            CompletableFuture.runAsync(() -> {
                                try {
                                    conn.connectSync();
                                } catch (Throwable t) {
                                    log.error("[McpClientManager] Auto-connect '{}' 예외: {}", cfg.name(), t.getMessage());
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    log.warn("[McpClientManager] Failed to load new MCP config {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[McpClientManager] Failed to scan MCP config dir: {}", e.getMessage());
        }
    }

    private static long getLastModified(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private McpServerConfig loadConfigFromDisk(String name) {
        Path file = resolveMcpDir().resolve(configFileName(name));
        if (!Files.isRegularFile(file)) return null;
        try {
            return objectMapper.readValue(file.toFile(), McpServerConfig.class);
        } catch (Exception e) {
            log.warn("[McpClientManager] Failed to reload config for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private void loadConfigs() {
        Path mcpDir = resolveMcpDir();
        if (!Files.isDirectory(mcpDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mcpDir, "*.json")) {
            for (Path file : stream) {
                try {
                    McpServerConfig cfg = objectMapper.readValue(file.toFile(), McpServerConfig.class);
                    connections.put(cfg.name(), new McpConnection(cfg, toolRegistry, objectMapper, this));
                    configLastModified.put(cfg.name(), getLastModified(file));
                } catch (Exception e) {
                    log.warn("[McpClientManager] Failed to load MCP config {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[McpClientManager] Failed to scan MCP config dir: {}", e.getMessage());
        }
    }

    private void saveConfig(McpServerConfig cfg) {
        Path dir = resolveMcpDir();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(configFileName(cfg.name()));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), cfg);
            configLastModified.put(cfg.name(), getLastModified(file));
        } catch (IOException e) {
            log.error("[McpClientManager] Failed to save config for '{}': {}", cfg.name(), e.getMessage());
        }
    }

    private void deleteConfigFile(String name) {
        Path file = resolveMcpDir().resolve(configFileName(name));
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[McpClientManager] Failed to delete config file for '{}': {}", name, e.getMessage());
        }
    }

    private String configFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".json";
    }

    private Path resolveMcpDir() {
        return Path.of(config.getDataDir()).resolve("mcp");
    }
}
