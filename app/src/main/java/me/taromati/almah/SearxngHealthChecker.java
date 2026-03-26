package me.taromati.almah;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ServiceInstaller;
import me.taromati.almah.setup.service.WindowsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * SearXNG 상태를 주기적으로 확인하고, 죽어있으면 복구를 시도한다.
 * NSSM의 자동 재시작이 1차 방어, 이 체크가 2차 방어.
 */
@Slf4j
@Component
public class SearxngHealthChecker {

    private volatile String cachedSearxngUrl;
    private volatile boolean recovered = false;

    /**
     * 60초마다 SearXNG 상태 확인. 첫 실행은 앱 시작 30초 후.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void checkSearxng() {
        String url = getSearxngUrl();
        if (url == null || url.isEmpty()) return;

        if (testConnection(url)) {
            if (recovered) {
                log.info("[SearXNG] 복구 확인 완료");
                recovered = false;
            }
            return;
        }

        log.warn("[SearXNG] 응답 없음. 복구를 시도합니다...");

        if (tryRecover(url)) {
            recovered = true;
            log.info("[SearXNG] 복구 완료");
        } else {
            log.warn("[SearXNG] 복구 실패. 웹 검색 기능이 제한됩니다. URL: {}", url);
        }
    }

    private boolean tryRecover(String url) {
        String os = ServiceInstaller.detectOs();

        switch (os) {
            case "windows" -> {
                // NSSM 서비스 시작 시도
                if (WindowsService.tryStartSearxng()) {
                    return waitForSearxng(url);
                }
            }
            case "macos", "linux" -> {
                // start.sh 재실행
                String selahHome = System.getenv("SELAH_HOME");
                if (selahHome == null || selahHome.isEmpty()) {
                    selahHome = System.getProperty("user.dir");
                }
                Path startScript = Path.of(selahHome, "searxng", "start.sh");
                if (Files.isExecutable(startScript)) {
                    try {
                        new ProcessBuilder(startScript.toString())
                                .directory(startScript.getParent().toFile())
                                .start();
                        return waitForSearxng(url);
                    } catch (Exception e) {
                        log.debug("[SearXNG] start.sh 실행 실패: {}", e.getMessage());
                    }
                }
            }
        }
        return false;
    }

    private boolean waitForSearxng(String url) {
        try { Thread.sleep(5000); } catch (InterruptedException e) { return false; }
        return testConnection(url);
    }

    private String getSearxngUrl() {
        if (cachedSearxngUrl != null) return cachedSearxngUrl;
        try {
            ConfigGenerator config = ConfigGenerator.fromExistingConfig(Path.of("config.yml"));
            cachedSearxngUrl = config.getSearxngUrl();
            return cachedSearxngUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean testConnection(String url) {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
