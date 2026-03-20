package me.taromati.almah.setup;

import me.taromati.almah.SelahApplication;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SelfUpdater {

    private static final String GITHUB_REPO = "taromati/selah";
    private static final String API_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

    public static void run() {
        ConsoleUi ui = new ConsoleUi();
        ui.section("Selah 업데이트");

        String currentVersion = SelahApplication.VERSION;
        ui.info("현재 버전: v" + currentVersion);

        // 최신 버전 확인
        ui.info("최신 버전 확인 중...");
        String latestVersion = fetchLatestVersion();
        if (latestVersion == null) {
            ui.error("최신 버전을 확인할 수 없습니다.");
            System.exit(1);
        }

        ui.info("최신 버전: v" + latestVersion);

        if (compareVersions(currentVersion, latestVersion) >= 0) {
            ui.success("이미 최신 버전입니다.");
            return;
        }

        // 다운로드
        String jarName = "selah-" + latestVersion + ".jar";
        String downloadUrl = "https://github.com/" + GITHUB_REPO
                + "/releases/download/v" + latestVersion + "/" + jarName;

        ui.info("다운로드 중: " + jarName);
        Path libDir = resolveLibDir();
        Path targetJar = libDir.resolve(jarName);

        try {
            downloadFile(downloadUrl, targetJar);
        } catch (Exception e) {
            ui.error("다운로드 실패: " + e.getMessage());
            System.exit(1);
        }

        // 이전 버전 JAR 삭제
        cleanOldJars(libDir, jarName);

        ui.success("업데이트 완료: v" + currentVersion + " → v" + latestVersion);
        ui.info("변경 사항을 적용하려면 Selah를 재시작해주세요.");
    }

    static String fetchLatestVersion() {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            // tag_name 파싱 (JSON 라이브러리 없이)
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"")
                    .matcher(response.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void downloadFile(String url, Path target) throws IOException, InterruptedException {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(target);
                throw new IOException("HTTP " + response.statusCode());
            }
        }
    }

    private static void cleanOldJars(Path libDir, String keepName) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(libDir, "selah-*.jar")) {
            for (Path jar : stream) {
                if (!jar.getFileName().toString().equals(keepName)) {
                    Files.deleteIfExists(jar);
                }
            }
        } catch (IOException ignored) {}
    }

    private static Path resolveLibDir() {
        // ~/.selah/lib/ 또는 현재 JAR이 있는 디렉토리
        Path selahHome = Path.of(System.getProperty("user.home"), ".selah", "lib");
        if (Files.isDirectory(selahHome)) return selahHome;

        // 현재 디렉토리 fallback
        return Path.of(".").toAbsolutePath();
    }

    /**
     * 시맨틱 버전 비교. a > b → 양수, a == b → 0, a < b → 음수
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return va - vb;
        }
        return 0;
    }
}
