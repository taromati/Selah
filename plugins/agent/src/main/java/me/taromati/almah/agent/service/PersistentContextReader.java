package me.taromati.almah.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 영속 컨텍스트 파일 리더.
 * agent-data/ 디렉토리에서 TOOLS.md, PERSONA.md, GUIDE.md, USER.md, skills/ 를 읽습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class PersistentContextReader {

    private static final int MAX_TOOLS_MD_LINES = 500;
    private static final int MAX_PERSONA_MD_LINES = 200;
    private static final int MAX_GUIDE_MD_LINES = 300;
    private static final int MAX_USER_MD_LINES = 200;
    // MAX_SKILL_CHARS 제거 — content는 skill(action=view) 도구로 전체 반환
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final AgentConfigProperties config;

    /**
     * TOOLS.md 읽기. 파일 없으면 null.
     */
    public String readToolsMd() {
        Path path = resolveDataDir().resolve("TOOLS.md");
        return readFileWithLineLimit(path, MAX_TOOLS_MD_LINES);
    }

    /**
     * TOOLS.md 쓰기. 디렉토리 없으면 자동 생성.
     */
    public void writeToolsMd(String content) {
        Path path = resolveDataDir().resolve("TOOLS.md");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            log.info("[PersistentContext] Wrote TOOLS.md ({} chars)", content.length());
        } catch (IOException e) {
            log.error("[PersistentContext] Failed to write TOOLS.md: {}", e.getMessage());
            throw new RuntimeException("Failed to write TOOLS.md", e);
        }
    }

    /**
     * GUIDE.md 읽기. 파일 없으면 null.
     */
    public String readGuideMd() {
        Path path = resolveDataDir().resolve("GUIDE.md");
        return readFileWithLineLimit(path, MAX_GUIDE_MD_LINES);
    }

    /**
     * GUIDE.md 쓰기. 디렉토리 없으면 자동 생성.
     */
    public void writeGuideMd(String content) {
        Path path = resolveDataDir().resolve("GUIDE.md");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            log.info("[PersistentContext] Wrote GUIDE.md ({} chars)", content.length());
        } catch (IOException e) {
            log.error("[PersistentContext] Failed to write GUIDE.md: {}", e.getMessage());
            throw new RuntimeException("Failed to write GUIDE.md", e);
        }
    }

    /**
     * PERSONA.md 읽기. 파일 없으면 null.
     */
    public String readPersonaMd() {
        Path path = resolveDataDir().resolve("PERSONA.md");
        return readFileWithLineLimit(path, MAX_PERSONA_MD_LINES);
    }

    /**
     * PERSONA.md 쓰기. 디렉토리 없으면 자동 생성.
     */
    public void writePersonaMd(String content) {
        Path path = resolveDataDir().resolve("PERSONA.md");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            log.info("[PersistentContext] Wrote PERSONA.md ({} chars)", content.length());
        } catch (IOException e) {
            log.error("[PersistentContext] Failed to write PERSONA.md: {}", e.getMessage());
            throw new RuntimeException("Failed to write PERSONA.md", e);
        }
    }

    /**
     * USER.md 읽기. 파일 없으면 null.
     */
    public String readUserMd() {
        Path path = resolveDataDir().resolve("USER.md");
        return readFileWithLineLimit(path, MAX_USER_MD_LINES);
    }

    /**
     * USER.md 쓰기. 디렉토리 없으면 자동 생성.
     */
    public void writeUserMd(String content) {
        Path path = resolveDataDir().resolve("USER.md");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            log.info("[PersistentContext] Wrote USER.md ({} chars)", content.length());
        } catch (IOException e) {
            log.error("[PersistentContext] Failed to write USER.md: {}", e.getMessage());
            throw new RuntimeException("Failed to write USER.md", e);
        }
    }

    /**
     * skills/ 디렉토리에서 active: true인 스킬만 수집.
     */
    public List<SkillFile> readActiveSkills() {
        Path skillsDir = resolveDataDir().resolve("skills");
        if (!Files.isDirectory(skillsDir)) return List.of();

        List<SkillFile> activeSkills = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(skillsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;

                SkillFile skill = parseSkillFile(skillMd);
                if (skill != null && skill.active()) {
                    activeSkills.add(skill);
                }
            }
        } catch (IOException e) {
            log.warn("[PersistentContext] Failed to read skills directory: {}", e.getMessage());
        }
        return activeSkills;
    }

    /**
     * 영속 컨텍스트 총 문자 수 추정 (컴팩션 토큰 계산용).
     */
    public int estimatePersistentContextChars() {
        int total = 0;
        String personaMd = readPersonaMd();
        if (personaMd != null) total += personaMd.length();
        String guideMd = readGuideMd();
        if (guideMd != null) total += guideMd.length();
        String toolsMd = readToolsMd();
        if (toolsMd != null) total += toolsMd.length();
        String userMd = readUserMd();
        if (userMd != null) total += userMd.length();
        for (SkillFile skill : readActiveSkills()) {
            total += skill.description() != null ? skill.description().length() : 0;
        }
        return total;
    }

    /**
     * SKILL.md frontmatter 파싱 (Jackson YAML).
     * <pre>
     * ---
     * name: skill-name
     * description: 설명
     * active: true
     * tools: exec, file_read
     * os: [darwin, linux]
     * requires:
     *   bins: [op]
     *   env: [OP_TOKEN]
     * mcp-server: server-name
     * env:
     *   KEY: value
     * install:
     *   - kind: brew
     *     formula: package
     *     bins: [cmd]
     *     label: Label
     * ---
     * 본문 내용
     * </pre>
     */
    @SuppressWarnings("unchecked")
    SkillFile parseSkillFile(Path skillMdPath) {
        try {
            String raw = Files.readString(skillMdPath);

            String name = null;
            String description = null;
            boolean active = false;
            List<String> tools = List.of();
            List<String> os = List.of();
            SkillFile.Requires requires = null;
            String mcpServer = null;
            Map<String, String> env = Map.of();
            List<SkillFile.InstallSpec> install = List.of();
            String content;

            String trimmed = raw.stripLeading();
            if (trimmed.startsWith("---")) {
                int firstSep = trimmed.indexOf("---");
                int secondSep = trimmed.indexOf("---", firstSep + 3);
                if (secondSep > 0) {
                    String frontmatterStr = trimmed.substring(firstSep + 3, secondSep).trim();
                    content = trimmed.substring(secondSep + 3).stripLeading();

                    Map<String, Object> fm = YAML_MAPPER.readValue(frontmatterStr, Map.class);
                    if (fm != null) {
                        name = stringVal(fm.get("name"));
                        description = stringVal(fm.get("description"));
                        active = boolVal(fm.get("active"));
                        tools = parseToolsList(fm.get("tools"));
                        os = toStringList(fm.get("os"));
                        mcpServer = stringVal(fm.get("mcp-server"));
                        env = toStringMap(fm.get("env"));
                        requires = parseRequires(fm.get("requires"));
                        install = parseInstallSpecs(fm.get("install"));
                    }
                } else {
                    content = raw;
                }
            } else {
                content = raw;
            }

            if (name == null || name.isEmpty()) {
                name = skillMdPath.getParent().getFileName().toString();
            }

            return new SkillFile(name, description, active, tools, content, os, requires, mcpServer, env, install);
        } catch (IOException e) {
            log.warn("[PersistentContext] Failed to parse skill file {}: {}", skillMdPath, e.getMessage());
            return null;
        }
    }

    /**
     * skills/ 디렉토리에서 모든 스킬 파일 수집 (active 여부 무관).
     */
    public List<SkillFile> readAllSkills() {
        Path skillsDir = resolveDataDir().resolve("skills");
        if (!Files.isDirectory(skillsDir)) return List.of();

        List<SkillFile> skills = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(skillsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;
                SkillFile skill = parseSkillFile(skillMd);
                if (skill != null) {
                    skills.add(skill);
                }
            }
        } catch (IOException e) {
            log.warn("[PersistentContext] Failed to read skills directory: {}", e.getMessage());
        }
        return skills;
    }

    /**
     * 스킬 디렉토리의 SKILL.md 경로 반환 (존재 여부 무관).
     */
    public Path resolveSkillPath(String skillName) {
        return resolveDataDir().resolve("skills").resolve(skillName).resolve("SKILL.md");
    }

    /**
     * skills 디렉토리 경로.
     */
    public Path resolveSkillsDir() {
        return resolveDataDir().resolve("skills");
    }

    // ── Frontmatter 파싱 유틸 ──

    private static String stringVal(Object obj) {
        return obj != null ? String.valueOf(obj) : null;
    }

    private static boolean boolVal(Object obj) {
        if (obj instanceof Boolean b) return b;
        if (obj instanceof String s) return "true".equalsIgnoreCase(s);
        return false;
    }

    /**
     * tools 필드 파싱: 콤마 구분 문자열 또는 YAML 리스트 지원.
     */
    private static List<String> parseToolsList(Object obj) {
        if (obj instanceof String s) {
            return Arrays.stream(s.split(","))
                    .map(String::trim).filter(t -> !t.isEmpty()).toList();
        }
        return toStringList(obj);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toStringMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static SkillFile.Requires parseRequires(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) return null;
        List<String> bins = toStringList(map.get("bins"));
        List<String> envList = toStringList(map.get("env"));
        if (bins.isEmpty() && envList.isEmpty()) return null;
        return new SkillFile.Requires(bins, envList);
    }

    @SuppressWarnings("unchecked")
    private static List<SkillFile.InstallSpec> parseInstallSpecs(Object obj) {
        if (!(obj instanceof List<?> list)) return List.of();
        List<SkillFile.InstallSpec> specs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                specs.add(new SkillFile.InstallSpec(
                        stringVal(map.get("kind")),
                        stringVal(map.get("formula")),
                        toStringList(map.get("bins")),
                        stringVal(map.get("label"))
                ));
            }
        }
        return specs;
    }

    private Path resolveDataDir() {
        return Path.of(config.getDataDir());
    }

    private String readFileWithLineLimit(Path path, int maxLines) {
        if (!Files.isRegularFile(path)) return null;
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.size() > maxLines) {
                lines = lines.subList(0, maxLines);
            }
            String result = String.join("\n", lines);
            return result.isBlank() ? null : result;
        } catch (IOException e) {
            log.warn("[PersistentContext] Failed to read {}: {}", path, e.getMessage());
            return null;
        }
    }
}
