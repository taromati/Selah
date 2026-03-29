package me.taromati.almah.agent.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.mcp.McpClientManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 스킬 중앙 컨트롤러.
 * CRUD + lastModified 캐시 + 게이팅 조율 + MCP 이벤트 리스닝.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SkillManager {

    /**
     * 캐시된 스킬 (파일 + 게이팅 결과 + lastModified).
     */
    public record CachedSkill(SkillFile skillFile, GatingResult gatingResult, long lastModified) {}

    private final PersistentContextReader reader;
    private final SkillGatingService gatingService;
    private final AgentConfigProperties config;
    private final Map<String, CachedSkill> cache = new ConcurrentHashMap<>();
    private final Set<String> rejectedInstallUrls = ConcurrentHashMap.newKeySet();

    @Autowired(required = false)
    private McpClientManager mcpClientManager;

    public SkillManager(PersistentContextReader reader, SkillGatingService gatingService,
                        AgentConfigProperties config) {
        this.reader = reader;
        this.gatingService = gatingService;
        this.config = config;
    }

    @PostConstruct
    void init() {
        loadAll();
        // MCP 이벤트 리스너 등록
        if (mcpClientManager != null) {
            mcpClientManager.addListener((serverName, connected) -> onMcpConnectionChanged(serverName));
        }
    }

    /**
     * 시작 시 전체 스킬 로드.
     */
    public void loadAll() {
        cache.clear();
        List<SkillFile> skills = reader.readAllSkills();
        for (SkillFile skill : skills) {
            Path skillMd = reader.resolveSkillPath(skill.name());
            long lastMod = getLastModified(skillMd);
            GatingResult gating = gatingService.evaluate(skill);
            cache.put(skill.name(), new CachedSkill(skill, gating, lastMod));
            log.debug("[SkillManager] Loaded '{}': {}", skill.name(), gating.status());
        }
        log.info("[SkillManager] Loaded {} skills ({} active)", cache.size(),
                cache.values().stream().filter(c -> c.gatingResult.status() == GatingResult.GatingStatus.ACTIVE).count());
    }

    /**
     * ACTIVE 상태인 스킬만 반환. lastModified가 변경된 스킬은 자동 리로드.
     */
    public List<SkillFile> getActiveSkills() {
        refreshStaleEntries();
        return cache.values().stream()
                .filter(c -> c.gatingResult.status() == GatingResult.GatingStatus.ACTIVE)
                .map(CachedSkill::skillFile)
                .toList();
    }

    /**
     * INSTALL_REQUIRED 상태인 스킬 반환 (캐시된 스킬 포함).
     */
    public List<CachedSkill> getInstallRequiredSkills() {
        refreshStaleEntries();
        return cache.values().stream()
                .filter(c -> c.gatingResult.status() == GatingResult.GatingStatus.INSTALL_REQUIRED)
                .toList();
    }

    /**
     * 전체 스킬 + 게이팅 상태 반환.
     */
    public List<CachedSkill> getAll() {
        refreshStaleEntries();
        return List.copyOf(cache.values());
    }

    /**
     * 스킬 이름으로 캐시 항목 조회.
     */
    public CachedSkill get(String name) {
        return cache.get(name);
    }

    /**
     * 새 스킬 생성 (디렉토리 + SKILL.md 작성).
     */
    public void create(String name, String content) throws IOException {
        Path skillDir = reader.resolveSkillsDir().resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
        reloadSkill(name);
        log.info("[SkillManager] Created skill: {}", name);
    }

    /**
     * 기존 스킬 수정 (SKILL.md 덮어쓰기 + 캐시 갱신).
     * name 필드와 디렉토리 이름이 다를 경우도 처리.
     */
    public void update(String name, String content) throws IOException {
        Path skillMd = resolveActualSkillMd(name);
        if (skillMd == null || !Files.isRegularFile(skillMd)) {
            throw new IOException("스킬 파일을 찾을 수 없습니다: " + name);
        }
        Files.writeString(skillMd, content);
        reloadSkill(name);
        log.info("[SkillManager] Updated skill: {}", name);
    }

    /**
     * name 필드 기준으로 실제 SKILL.md 경로를 찾는다.
     * 디렉토리 이름이 다를 경우 (예: mcp_wrap vs mcp-wrap) 전체 스캔.
     */
    Path resolveActualSkillMd(String name) {
        Path direct = reader.resolveSkillPath(name);
        if (Files.isRegularFile(direct)) return direct;

        Path skillsDir = reader.resolveSkillsDir();
        if (!Files.isDirectory(skillsDir)) return null;
        try (var dirs = Files.newDirectoryStream(skillsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                Path md = dir.resolve("SKILL.md");
                if (Files.isRegularFile(md)) {
                    SkillFile parsed = reader.parseSkillFile(md);
                    if (parsed != null && name.equals(parsed.name())) {
                        return md;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[SkillManager] Failed to scan skills dir for '{}': {}", name, e.getMessage());
        }
        return null;
    }

    /**
     * 스킬 삭제 (디렉토리 전체 삭제).
     */
    public void remove(String name) throws IOException {
        // name 필드와 디렉토리 이름이 다를 수 있으므로 (mcp_wrap vs mcp-wrap) 실제 디렉토리를 탐색
        Path skillDir = reader.resolveSkillsDir().resolve(name);
        if (!Files.isDirectory(skillDir)) {
            // name으로 못 찾으면 전체 스킬 디렉토리에서 name 매칭으로 탐색
            Path skillsDir = reader.resolveSkillsDir();
            if (Files.isDirectory(skillsDir)) {
                try (var dirs = Files.newDirectoryStream(skillsDir)) {
                    for (Path dir : dirs) {
                        if (!Files.isDirectory(dir)) continue;
                        Path md = dir.resolve("SKILL.md");
                        if (Files.isRegularFile(md)) {
                            SkillFile parsed = reader.parseSkillFile(md);
                            if (parsed != null && name.equals(parsed.name())) {
                                skillDir = dir;
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (Files.isDirectory(skillDir)) {
            deleteRecursive(skillDir);
        }
        cache.remove(name);
        log.info("[SkillManager] Removed skill: {}", name);
    }

    /**
     * 스킬 활성화 — frontmatter의 active 필드를 true로 변경.
     */
    public void enable(String name) throws IOException {
        toggleActive(name, true);
    }

    /**
     * 스킬 비활성화 — frontmatter의 active 필드를 false로 변경.
     */
    public void disable(String name) throws IOException {
        toggleActive(name, false);
    }

    /**
     * MCP 연결 변경 시 관련 스킬 재게이팅.
     */
    public void onMcpConnectionChanged(String serverName) {
        log.debug("[SkillManager] MCP connection changed: {}", serverName);
        for (var entry : cache.entrySet()) {
            SkillFile skill = entry.getValue().skillFile;
            if (serverName.equals(skill.mcpServer())) {
                GatingResult newGating = gatingService.evaluate(skill);
                cache.put(entry.getKey(), new CachedSkill(skill, newGating, entry.getValue().lastModified));
                log.info("[SkillManager] Re-gated '{}': {} → {}", skill.name(),
                        entry.getValue().gatingResult.status(), newGating.status());
            }
        }
    }

    /**
     * 단일 스킬 리로드.
     */
    void reloadSkill(String name) {
        Path skillMd = reader.resolveSkillPath(name);
        if (!Files.isRegularFile(skillMd)) {
            cache.remove(name);
            return;
        }
        SkillFile skill = reader.parseSkillFile(skillMd);
        if (skill == null) {
            cache.remove(name);
            return;
        }
        long lastMod = getLastModified(skillMd);
        GatingResult gating = gatingService.evaluate(skill);
        cache.put(name, new CachedSkill(skill, gating, lastMod));
    }

    // ── 설치 거부 추적 ──

    public void recordInstallRejection(String url) {
        rejectedInstallUrls.add(url);
    }

    public boolean wasInstallRejected(String url) {
        return rejectedInstallUrls.contains(url);
    }

    public void clearInstallRejections() {
        rejectedInstallUrls.clear();
    }

    // ── Private ──

    private void refreshStaleEntries() {
        for (var entry : cache.entrySet()) {
            Path skillMd = reader.resolveSkillPath(entry.getKey());
            long currentMod = getLastModified(skillMd);
            if (currentMod != entry.getValue().lastModified) {
                log.debug("[SkillManager] Skill '{}' modified, reloading", entry.getKey());
                reloadSkill(entry.getKey());
            }
        }

        // 새로 추가된 스킬 디렉토리 감지
        List<SkillFile> allOnDisk = reader.readAllSkills();
        for (SkillFile skill : allOnDisk) {
            if (!cache.containsKey(skill.name())) {
                Path skillMd = reader.resolveSkillPath(skill.name());
                long lastMod = getLastModified(skillMd);
                GatingResult gating = gatingService.evaluate(skill);
                cache.put(skill.name(), new CachedSkill(skill, gating, lastMod));
                log.info("[SkillManager] Discovered new skill: {}", skill.name());
            }
        }
    }

    private void toggleActive(String name, boolean active) throws IOException {
        Path skillMd = reader.resolveSkillPath(name);
        if (!Files.isRegularFile(skillMd)) {
            throw new IOException("스킬을 찾을 수 없습니다: " + name);
        }

        String content = Files.readString(skillMd);
        // active 필드가 있으면 교체, 없으면 frontmatter에 추가
        if (content.contains("active:")) {
            content = content.replaceFirst("active:\\s*\\w+", "active: " + active);
        } else if (content.startsWith("---")) {
            int secondSep = content.indexOf("---", 3);
            if (secondSep > 0) {
                content = content.substring(0, secondSep) + "active: " + active + "\n" + content.substring(secondSep);
            }
        }
        Files.writeString(skillMd, content);
        reloadSkill(name);
        log.info("[SkillManager] Skill '{}' active → {}", name, active);
    }

    private static long getLastModified(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var entries = Files.walk(dir)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.delete(path); } catch (IOException ignored) {}
                    });
        }
    }
}
