package me.taromati.almah.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 외부 소스에서 스킬 다운로드 + OpenClaw→Almah 포맷 변환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SkillImporter {

    private final AgentConfigProperties config;
    private final SkillManager skillManager;
    private final ObjectMapper objectMapper;
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * ClawHub 레지스트리에서 스킬 검색.
     *
     * @param query 검색어
     * @return 검색 결과 (slug, displayName, summary, version)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchClawHub(String query) throws IOException, InterruptedException {
        String searchUrl = config.getSkill().getClawhubRegistry()
                + "/api/v1/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("ClawHub 검색 오류 (HTTP " + response.statusCode() + ")");
        }

        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        Object results = body.get("results");
        if (results instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    /**
     * 외부 소스에서 스킬 가져오기.
     *
     * @param sourceType "github", "url", "clawhub"
     * @param url        소스 URL 또는 ClawHub slug
     * @return 가져온 스킬 이름
     */
    public String importSkill(String sourceType, String url) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("skill-import-");
        try {
            return switch (sourceType) {
                case "github" -> importFromGithub(url, tempDir);
                case "url" -> importFromUrl(url, tempDir);
                case "clawhub" -> importFromClawHub(url, tempDir);
                default -> throw new IllegalArgumentException("지원하지 않는 소스: " + sourceType);
            };
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private String importFromGithub(String url, Path tempDir) throws IOException, InterruptedException {
        // GitHub raw URL이면 직접 다운로드
        if (url.contains("raw.githubusercontent.com") || url.endsWith(".md")) {
            return importFromUrl(url, tempDir);
        }

        // blob URL → raw URL 변환
        if (url.contains("/blob/")) {
            String rawUrl = url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/");
            return importFromUrl(rawUrl, tempDir);
        }

        // GitHub 저장소의 특정 디렉토리 (예: user/repo/tree/main/skills/my-skill)
        // API URL로 변환: https://api.github.com/repos/{owner}/{repo}/contents/{path}
        String apiUrl = convertGithubToApiUrl(url);
        if (apiUrl != null) {
            return importFromGithubApi(apiUrl, tempDir);
        }

        // git clone fallback
        return importViaGitClone(url, tempDir);
    }

    @SuppressWarnings("unchecked")
    private String importFromGithubApi(String apiUrl, Path tempDir) throws IOException, InterruptedException {
        downloadGithubContents(apiUrl, tempDir, 0);
        return convertAndInstall(findSkillDir(tempDir));
    }

    /**
     * GitHub Contents API 재귀 다운로드.
     */
    @SuppressWarnings("unchecked")
    private void downloadGithubContents(String apiUrl, Path targetDir, int depth)
            throws IOException, InterruptedException {
        if (depth > 3) return;  // 안전장치

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/vnd.github.v3+json")
                .timeout(Duration.ofSeconds(30))
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API 오류 (HTTP " + response.statusCode() + ")");
        }

        Files.createDirectories(targetDir);
        List<Map<String, Object>> items = objectMapper.readValue(response.body(), List.class);
        for (Map<String, Object> item : items) {
            String name = (String) item.get("name");
            String type = (String) item.get("type");
            if ("dir".equals(type)) {
                String subUrl = (String) item.get("url");
                Path subDir = targetDir.resolve(name);
                Files.createDirectories(subDir);
                downloadGithubContents(subUrl, subDir, depth + 1);
            } else if ("file".equals(type)) {
                String downloadUrl = (String) item.get("download_url");
                if (downloadUrl != null) {
                    downloadFile(downloadUrl, targetDir.resolve(name));
                }
            }
        }
    }

    private String importFromUrl(String url, Path tempDir) throws IOException, InterruptedException {
        String lower = url.toLowerCase();

        // zip 아카이브
        if (lower.endsWith(".zip")) {
            Path downloaded = tempDir.resolve("archive.zip");
            downloadFile(url, downloaded);
            Path extractDir = tempDir.resolve("extracted");
            extractZip(downloaded, extractDir);
            return convertAndInstall(findSkillDir(extractDir));
        }

        // tar.gz / tgz 아카이브
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            Path downloaded = tempDir.resolve("archive.tar.gz");
            downloadFile(url, downloaded);
            Path extractDir = tempDir.resolve("extracted");
            extractTarGz(downloaded, extractDir);
            return convertAndInstall(findSkillDir(extractDir));
        }

        // 단일 파일 다운로드
        Path downloaded = tempDir.resolve("SKILL.md");
        downloadFile(url, downloaded);

        // SKILL.md 형식이면 바로 설치
        String content = Files.readString(downloaded);
        if (content.stripLeading().startsWith("---")) {
            return convertAndInstall(tempDir);
        }

        // metadata.json 형식이면 변환
        return convertAndInstall(tempDir);
    }

    /**
     * ClawHub 레지스트리에서 스킬 가져오기.
     *
     * @param slug ClawHub 스킬 slug
     */
    private String importFromClawHub(String slug, Path tempDir) throws IOException, InterruptedException {
        String downloadUrl = config.getSkill().getClawhubRegistry()
                + "/api/v1/download?slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
        Path zipFile = tempDir.resolve("skill.zip");
        downloadFile(downloadUrl, zipFile);

        Path extractDir = tempDir.resolve("extracted");
        extractZip(zipFile, extractDir);

        Path skillDir = findSkillDir(extractDir);
        return convertAndInstall(skillDir);
    }

    private String importViaGitClone(String url, Path tempDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth=1", url, tempDir.resolve("repo").toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        boolean done = proc.waitFor(60, TimeUnit.SECONDS);
        if (!done || proc.exitValue() != 0) {
            throw new IOException("git clone 실패: " + url);
        }

        Path repoDir = tempDir.resolve("repo");
        Path skillDir = findSkillDir(repoDir);
        copyDirectory(skillDir, tempDir.resolve("skill"));
        return convertAndInstall(tempDir.resolve("skill"));
    }

    /**
     * OpenClaw→Almah 변환 후 설치.
     */
    private String convertAndInstall(Path dir) throws IOException {
        Path skillMd = dir.resolve("SKILL.md");
        Path metadataFile = dir.resolve("metadata.json");
        Path promptFile = dir.resolve("prompt.md");

        // SKILL.md가 있으면 OpenClaw 형식인지 확인 후 변환
        if (Files.isRegularFile(skillMd)) {
            String content = Files.readString(skillMd);
            if (isOpenClawSkillMd(content)) {
                String converted = convertOpenClawSkillMd(content);
                Files.writeString(skillMd, converted);
            }
            return installAlmahSkill(dir);
        }

        // OpenClaw metadata.json 형식 변환
        if (Files.isRegularFile(metadataFile)) {
            String almahContent = convertOpenClawToAlmah(metadataFile, promptFile);
            Files.writeString(dir.resolve("SKILL.md"), almahContent);
            return installAlmahSkill(dir);
        }

        throw new IOException("인식할 수 없는 스킬 형식 (SKILL.md 또는 metadata.json이 필요합니다)");
    }

    /**
     * OpenClaw metadata.json → Almah SKILL.md frontmatter 변환.
     */
    @SuppressWarnings("unchecked")
    private String convertOpenClawToAlmah(Path metadataFile, Path promptFile) throws IOException {
        Map<String, Object> meta = objectMapper.readValue(metadataFile.toFile(), Map.class);

        StringBuilder sb = new StringBuilder("---\n");
        appendYamlField(sb, "name", meta.get("name"));
        appendYamlField(sb, "description", meta.get("description"));
        sb.append("active: true\n");

        // tools
        Object toolsObj = meta.get("tools");
        if (toolsObj instanceof List<?> toolsList && !toolsList.isEmpty()) {
            sb.append("tools: ").append(String.join(", ", toolsList.stream().map(String::valueOf).toList())).append("\n");
        }

        // requirements → os, requires
        Object reqObj = meta.get("requirements");
        if (reqObj instanceof Map<?, ?> req) {
            Object osObj = req.get("os");
            if (osObj instanceof List<?> osList && !osList.isEmpty()) {
                sb.append("os: [").append(String.join(", ", osList.stream().map(String::valueOf).toList())).append("]\n");
            }
            Object binsObj = req.get("binaries");
            Object envObj = req.get("env");
            if (binsObj != null || envObj != null) {
                sb.append("requires:\n");
                if (binsObj instanceof List<?> binsList && !binsList.isEmpty()) {
                    sb.append("  bins: [").append(String.join(", ", binsList.stream().map(String::valueOf).toList())).append("]\n");
                }
                if (envObj instanceof List<?> envList && !envList.isEmpty()) {
                    sb.append("  env: [").append(String.join(", ", envList.stream().map(String::valueOf).toList())).append("]\n");
                }
            }
            Object mcpObj = req.get("mcpServer");
            if (mcpObj != null) {
                sb.append("mcp-server: ").append(mcpObj).append("\n");
            }
        }

        // env
        Object envObj = meta.get("env");
        if (envObj instanceof Map<?, ?> envMap && !envMap.isEmpty()) {
            sb.append("env:\n");
            envMap.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }

        // install
        Object installObj = meta.get("install");
        if (installObj instanceof List<?> installList && !installList.isEmpty()) {
            sb.append("install:\n");
            for (Object item : installList) {
                if (item instanceof Map<?, ?> spec) {
                    sb.append("  - kind: ").append(spec.get("kind")).append("\n");
                    sb.append("    formula: ").append(spec.get("formula")).append("\n");
                    Object bins = spec.get("bins");
                    if (bins instanceof List<?> binsList) {
                        sb.append("    bins: [").append(String.join(", ", binsList.stream().map(String::valueOf).toList())).append("]\n");
                    }
                    Object label = spec.get("label");
                    if (label != null) {
                        sb.append("    label: ").append(label).append("\n");
                    }
                }
            }
        }

        sb.append("---\n");

        // 본문
        if (Files.isRegularFile(promptFile)) {
            sb.append(Files.readString(promptFile));
        }

        return sb.toString();
    }

    /**
     * OpenClaw SKILL.md frontmatter 형식 감지.
     * frontmatter에 "metadata:" 필드와 "openclaw" 키가 있으면 OpenClaw 형식.
     */
    private static boolean isOpenClawSkillMd(String content) {
        if (!content.stripLeading().startsWith("---")) return false;
        int start = content.indexOf("---");
        int end = content.indexOf("---", start + 3);
        if (end < 0) return false;
        String fm = content.substring(start + 3, end);
        return fm.contains("metadata:") && fm.contains("openclaw");
    }

    /**
     * OpenClaw SKILL.md frontmatter → Almah 형식 변환.
     * <pre>
     * OpenClaw:
     *   metadata: { "openclaw": { "os": [...], "requires": {...}, "install": [...] } }
     * Almah:
     *   os: [...]
     *   requires: { bins: [...], env: [...] }
     *   install: [...]
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private String convertOpenClawSkillMd(String content) throws IOException {
        int fmStart = content.indexOf("---") + 3;
        int fmEnd = content.indexOf("---", fmStart);
        String frontmatter = content.substring(fmStart, fmEnd).trim();
        String body = content.substring(fmEnd + 3);

        Map<String, Object> fm = YAML_MAPPER.readValue(frontmatter, Map.class);

        StringBuilder sb = new StringBuilder("---\n");
        appendYamlField(sb, "name", fm.get("name"));
        appendYamlField(sb, "description", fm.get("description"));
        sb.append("active: true\n");

        // tools 보존
        Object toolsObj = fm.get("tools");
        if (toolsObj instanceof List<?> tl && !tl.isEmpty()) {
            sb.append("tools: ").append(String.join(", ", tl.stream().map(String::valueOf).toList())).append("\n");
        } else if (toolsObj instanceof String ts && !ts.isBlank()) {
            sb.append("tools: ").append(ts).append("\n");
        }

        // metadata.openclaw 추출
        Map<String, Object> openclaw = extractOpenClawMetadata(fm);

        if (openclaw != null) {
            // os
            if (openclaw.get("os") instanceof List<?> osList && !osList.isEmpty()) {
                sb.append("os: [").append(String.join(", ", osList.stream().map(String::valueOf).toList())).append("]\n");
            }

            // requires (bins, env)
            if (openclaw.get("requires") instanceof Map<?, ?> req) {
                Object bins = req.get("bins");
                Object env = req.get("env");
                if (bins != null || env != null) {
                    sb.append("requires:\n");
                    if (bins instanceof List<?> binsList && !binsList.isEmpty()) {
                        sb.append("  bins: [").append(String.join(", ", binsList.stream().map(String::valueOf).toList())).append("]\n");
                    }
                    if (env instanceof List<?> envList && !envList.isEmpty()) {
                        sb.append("  env: [").append(String.join(", ", envList.stream().map(String::valueOf).toList())).append("]\n");
                    }
                }
                if (req.get("mcpServer") != null) {
                    sb.append("mcp-server: ").append(req.get("mcpServer")).append("\n");
                }
            }

            // install
            if (openclaw.get("install") instanceof List<?> installList && !installList.isEmpty()) {
                sb.append("install:\n");
                for (Object item : installList) {
                    if (item instanceof Map<?, ?> spec) {
                        sb.append("  - kind: ").append(spec.get("kind")).append("\n");
                        if (spec.get("formula") != null) sb.append("    formula: ").append(spec.get("formula")).append("\n");
                        if (spec.get("package") != null) sb.append("    formula: ").append(spec.get("package")).append("\n");
                        if (spec.get("bins") instanceof List<?> binsList) {
                            sb.append("    bins: [").append(String.join(", ", binsList.stream().map(String::valueOf).toList())).append("]\n");
                        }
                        if (spec.get("label") != null) sb.append("    label: ").append(spec.get("label")).append("\n");
                    }
                }
            }
        }

        sb.append("---\n");
        sb.append(body);
        return sb.toString();
    }

    /**
     * frontmatter에서 openclaw 메타데이터 추출.
     * metadata가 Map이면 직접, JSON 문자열이면 파싱.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractOpenClawMetadata(Map<String, Object> fm) throws IOException {
        Object metaObj = fm.get("metadata");
        if (metaObj instanceof Map<?, ?> metaMap) {
            return (Map<String, Object>) metaMap.get("openclaw");
        }
        if (metaObj instanceof String metaStr) {
            Map<String, Object> parsed = objectMapper.readValue(metaStr, Map.class);
            return (Map<String, Object>) parsed.get("openclaw");
        }
        return null;
    }

    /**
     * Almah SKILL.md 형식의 스킬을 skills/ 디렉토리에 설치.
     */
    private String installAlmahSkill(Path dir) throws IOException {
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            throw new IOException("SKILL.md가 없습니다");
        }

        // 스킬 이름 추출
        String content = Files.readString(skillMd);
        String name = extractSkillName(content, dir);

        // skills 디렉토리에 복사
        Path targetDir = Path.of(config.getDataDir()).resolve("skills").resolve(name);
        Files.createDirectories(targetDir);

        // SKILL.md 복사
        Files.copy(skillMd, targetDir.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);

        // references 디렉토리가 있으면 복사
        Path refsDir = dir.resolve("references");
        if (Files.isDirectory(refsDir)) {
            copyDirectory(refsDir, targetDir.resolve("references"));
        }

        // SkillManager 리로드
        skillManager.reloadSkill(name);

        log.info("[SkillImporter] Installed skill: {}", name);
        return name;
    }

    // ── 아카이브 추출 ──

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (var zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    throw new IOException("잘못된 zip 엔트리 경로: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved);
                }
            }
        }
    }

    private void extractTarGz(Path tarGzFile, Path targetDir) throws IOException, InterruptedException {
        Files.createDirectories(targetDir);
        var pb = new ProcessBuilder("tar", "xzf",
                tarGzFile.toAbsolutePath().toString(),
                "-C", targetDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) {
            throw new IOException("tar 추출 실패: " + tarGzFile);
        }
    }

    /**
     * 추출된 디렉토리에서 스킬 루트 디렉토리 탐색.
     * 단일 래퍼 디렉토리 자동 진입, SKILL.md 우선, metadata.json fallback.
     */
    private static Path findSkillDir(Path dir) throws IOException {
        // 단일 래퍼 디렉토리 진입 (e.g., skill-v1.0/)
        Path[] children;
        try (Stream<Path> s = Files.list(dir)) {
            children = s.toArray(Path[]::new);
        }
        if (children.length == 1 && Files.isDirectory(children[0])) {
            dir = children[0];
        }

        // SKILL.md 우선 탐색
        try (var walk = Files.walk(dir, 5)) {
            Optional<Path> skillMd = walk
                    .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .findFirst();
            if (skillMd.isPresent()) return skillMd.get().getParent();
        }

        // metadata.json fallback
        try (var walk = Files.walk(dir, 5)) {
            Optional<Path> meta = walk
                    .filter(p -> p.getFileName().toString().equals("metadata.json"))
                    .filter(Files::isRegularFile)
                    .findFirst();
            if (meta.isPresent()) return meta.get().getParent();
        }

        return dir; // 못 찾으면 루트 디렉토리 반환 → convertAndInstall이 에러 처리
    }

    // ── 유틸리티 ──

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        long maxBytes = config.getFile().getMaxSkillDownloadMb() * 1024L * 1024L;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("다운로드 실패 (HTTP " + response.statusCode() + "): " + url);
        }

        // Content-Length 사전 체크
        response.headers().firstValueAsLong("Content-Length").ifPresent(contentLength -> {
            if (contentLength > maxBytes) {
                throw new UncheckedIOException(new IOException(
                        "파일 크기 초과 (Content-Length: " + (contentLength / 1024 / 1024) + "MB, 제한: "
                                + config.getFile().getMaxSkillDownloadMb() + "MB): " + url));
            }
        });

        Files.createDirectories(target.getParent());
        try (InputStream is = response.body()) {
            // 스트리밍 중 크기 감시
            long totalRead = 0;
            byte[] buf = new byte[8192];
            try (var out = Files.newOutputStream(target)) {
                int n;
                while ((n = is.read(buf)) != -1) {
                    totalRead += n;
                    if (totalRead > maxBytes) {
                        out.close();
                        Files.deleteIfExists(target);
                        throw new IOException("파일 크기 초과 (수신: " + (totalRead / 1024 / 1024)
                                + "MB+, 제한: " + config.getFile().getMaxSkillDownloadMb() + "MB): " + url);
                    }
                    out.write(buf, 0, n);
                }
            }
        }
    }

    private static String convertGithubToApiUrl(String url) {
        if (!url.contains("github.com")) return null;

        String path = url.replaceFirst("https?://github\\.com/", "");
        String[] parts = path.split("/");
        if (parts.length < 2) return null;

        String owner = parts[0];
        String repo = parts[1];

        // github.com/{owner}/{repo}/tree/{branch}/{path}
        if (parts.length >= 4 && "tree".equals(parts[2])) {
            String branch = parts[3];
            String contentPath = String.join("/", Arrays.copyOfRange(parts, 4, parts.length));
            return String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                    owner, repo, contentPath, branch);
        }

        // github.com/{owner}/{repo}/blob/{branch}/{path} → importFromGithub에서 raw URL로 처리
        if (parts.length >= 5 && "blob".equals(parts[2])) {
            return null;
        }

        // github.com/{owner}/{repo} (루트)
        if (parts.length == 2) {
            return String.format("https://api.github.com/repos/%s/%s/contents/", owner, repo);
        }

        return null;
    }

    private static String extractSkillName(String content, Path dir) {
        // frontmatter에서 name 필드 추출
        if (content.stripLeading().startsWith("---")) {
            int secondSep = content.indexOf("---", content.indexOf("---") + 3);
            if (secondSep > 0) {
                String fm = content.substring(3, secondSep);
                for (String line : fm.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("name:")) {
                        String name = line.substring(5).trim();
                        if (!name.isEmpty()) return name;
                    }
                }
            }
        }
        return dir.getFileName().toString();
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(src -> {
                Path dest = target.resolve(source.relativize(src));
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("[SkillImporter] 파일 복사 실패: {}", e.getMessage());
                }
            });
        }
    }

    private static void appendYamlField(StringBuilder sb, String key, Object value) {
        if (value != null) {
            sb.append(key).append(": ").append(value).append("\n");
        }
    }

    private static void deleteRecursive(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
