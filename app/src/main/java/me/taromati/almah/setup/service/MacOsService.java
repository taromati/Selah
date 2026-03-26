package me.taromati.almah.setup.service;

import me.taromati.almah.setup.ConsoleUi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MacOsService {

    private static final String LABEL = "me.taromati.selah";
    private static final Path PLIST_PATH = Path.of(
            System.getProperty("user.home"), "Library/LaunchAgents", LABEL + ".plist");

    public static void install(ConsoleUi ui) throws IOException, InterruptedException {
        Path selahHome = resolveSelahHome();
        Path wrapper = selahHome.resolve("bin/selah");
        if (!Files.isExecutable(wrapper)) {
            throw new IllegalStateException("래퍼 스크립트를 찾을 수 없습니다: " + wrapper);
        }

        if (me.taromati.almah.setup.ServiceInstaller.isSelahRunning()) {
            ui.info("Selah가 이미 실행 중입니다. 서비스를 재등록하면 자동 재시작됩니다.");
        }

        // 기존 서비스가 등록되어 있으면 먼저 해제
        {
            var proc = new ProcessBuilder("launchctl", "bootout",
                    "gui/" + getUid() + "/" + LABEL).inheritIO().start();
            proc.waitFor(); // 실패해도 무시 (미등록 상태일 수 있음)
        }

        // XML 특수문자 이스케이핑
        String wrapperPath = xmlEscape(wrapper.toString());
        String homePath = xmlEscape(selahHome.toString());

        String plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
                "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                    </array>
                    <key>WorkingDirectory</key>
                    <string>%s</string>
                    <key>EnvironmentVariables</key>
                    <dict>
                        <key>SELAH_HOME</key>
                        <string>%s</string>
                    </dict>
                    <key>RunAtLoad</key>
                    <true/>
                    <key>KeepAlive</key>
                    <dict>
                        <key>SuccessfulExit</key>
                        <false/>
                    </dict>
                    <key>ThrottleInterval</key>
                    <integer>10</integer>
                    <key>StandardOutPath</key>
                    <string>/dev/null</string>
                    <key>StandardErrorPath</key>
                    <string>/dev/null</string>
                </dict>
                </plist>
                """.formatted(LABEL, wrapperPath, homePath, homePath);

        Files.createDirectories(PLIST_PATH.getParent());
        Files.createDirectories(selahHome.resolve("logs"));
        Files.writeString(PLIST_PATH, plist);

        // launchctl bootstrap (load는 deprecated)
        var proc = new ProcessBuilder("launchctl", "bootstrap",
                "gui/" + getUid(), PLIST_PATH.toString())
                .inheritIO().start();
        int exitCode = proc.waitFor();

        if (exitCode == 0) {
            ui.success("LaunchAgent 등록 완료: " + PLIST_PATH);
            ui.info("시스템 로그인 시 자동 시작됩니다.");
        } else {
            ui.error("launchctl bootstrap 실패 (exit code: " + exitCode + ")");
        }
    }

    public static void uninstall(ConsoleUi ui) throws IOException, InterruptedException {
        if (!Files.exists(PLIST_PATH)) {
            ui.warn("서비스가 등록되어 있지 않습니다.");
            return;
        }

        // bootout으로 서비스 중지 + 해제
        var proc = new ProcessBuilder("launchctl", "bootout",
                "gui/" + getUid() + "/" + LABEL)
                .inheritIO().start();
        int exitCode = proc.waitFor();

        if (exitCode != 0) {
            ui.warn("서비스 중지에 문제가 있을 수 있습니다 (exit code: " + exitCode + ")");
        }

        Files.deleteIfExists(PLIST_PATH);
        ui.success("LaunchAgent 해제 완료");
    }

    private static Path resolveSelahHome() {
        String selahHome = System.getenv("SELAH_HOME");
        if (selahHome != null && !selahHome.isEmpty()) return Path.of(selahHome).toAbsolutePath();
        return Path.of("").toAbsolutePath();
    }

    private static String getUid() throws IOException {
        var proc = new ProcessBuilder("id", "-u").start();
        return new String(proc.getInputStream().readAllBytes()).trim();
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
