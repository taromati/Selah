package me.taromati.almah.setup;

import me.taromati.almah.setup.service.LinuxService;
import me.taromati.almah.setup.service.MacOsService;
import me.taromati.almah.setup.service.WindowsService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OS 감지 + 서비스 등록/해제 위임.
 */
public class ServiceInstaller {

    /** Selah가 이미 실행 중인지 health API로 확인 */
    public static boolean isSelahRunning() {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:6060/api/system/health"))
                    .GET().timeout(Duration.ofSeconds(3)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static void install() {
        ConsoleUi ui = new ConsoleUi();
        ui.section("서비스 등록");

        String os = detectOs();
        ui.info("OS 감지: " + os);

        try {
            switch (os) {
                case "macos" -> MacOsService.install(ui);
                case "linux" -> LinuxService.install(ui);
                case "windows" -> WindowsService.install(ui);
                default -> throw new RuntimeException(
                        "지원하지 않는 OS입니다: " + System.getProperty("os.name"));
            }
        } catch (Exception e) {
            throw new RuntimeException("서비스 등록 실패: " + e.getMessage(), e);
        }
    }

    public static void uninstall() {
        ConsoleUi ui = new ConsoleUi();
        ui.section("서비스 해제");

        String os = detectOs();
        ui.info("OS 감지: " + os);

        try {
            switch (os) {
                case "macos" -> MacOsService.uninstall(ui);
                case "linux" -> LinuxService.uninstall(ui);
                case "windows" -> WindowsService.uninstall(ui);
                default -> throw new RuntimeException(
                        "지원하지 않는 OS입니다: " + System.getProperty("os.name"));
            }
        } catch (Exception e) {
            throw new RuntimeException("서비스 해제 실패: " + e.getMessage(), e);
        }
    }

    public static String detectOs() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) return "macos";
        if (osName.contains("linux")) return "linux";
        if (osName.contains("windows")) return "windows";
        return "unknown";
    }
}
