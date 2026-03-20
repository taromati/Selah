package me.taromati.almah.setup;

import me.taromati.almah.setup.service.LinuxService;
import me.taromati.almah.setup.service.MacOsService;
import me.taromati.almah.setup.service.WindowsService;

/**
 * OS 감지 + 서비스 등록/해제 위임.
 */
public class ServiceInstaller {

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
