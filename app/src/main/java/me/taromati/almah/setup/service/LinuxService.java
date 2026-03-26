package me.taromati.almah.setup.service;

import me.taromati.almah.setup.ConsoleUi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LinuxService {

    private static final String SERVICE_NAME = "selah";
    private static final Path SERVICE_PATH = Path.of(
            System.getProperty("user.home"), ".config/systemd/user", SERVICE_NAME + ".service");

    public static void install(ConsoleUi ui) throws IOException, InterruptedException {
        Path selahHome = resolveSelahHome();
        Path wrapper = selahHome.resolve("bin/selah");
        if (!Files.isExecutable(wrapper)) {
            throw new IllegalStateException("래퍼 스크립트를 찾을 수 없습니다: " + wrapper);
        }

        if (me.taromati.almah.setup.ServiceInstaller.isSelahRunning()) {
            ui.info("Selah가 이미 실행 중입니다. 서비스를 재등록하면 자동 재시작됩니다.");
        }

        // 경로 공백 이스케이핑 (systemd unit에서 필요)
        String escapedWrapper = wrapper.toString().replace(" ", "\\x20");
        String escapedHome = selahHome.toString().replace(" ", "\\x20");

        String unit = """
                [Unit]
                Description=Selah AI Agent Bot

                [Service]
                Type=simple
                WorkingDirectory=%s
                Environment=SELAH_HOME=%s
                ExecStart=%s
                Restart=on-failure
                RestartSec=10
                StandardOutput=null
                StandardError=null

                [Install]
                WantedBy=default.target
                """.formatted(escapedHome, escapedHome, escapedWrapper);

        Files.createDirectories(SERVICE_PATH.getParent());
        Files.createDirectories(selahHome.resolve("logs"));
        Files.writeString(SERVICE_PATH, unit);

        exec("systemctl", "--user", "daemon-reload");
        int enableCode = exec("systemctl", "--user", "enable", SERVICE_NAME);
        // restart: 이미 실행 중이면 재시작, 아니면 시작. start는 이미 running이면 무시하여 설정 변경 미반영
        int startCode = exec("systemctl", "--user", "restart", SERVICE_NAME);

        if (enableCode == 0 && startCode == 0) {
            ui.success("systemd 사용자 서비스 등록 완료: " + SERVICE_PATH);

            // loginctl enable-linger 자동 실행 (헤드리스 서버 지원)
            int lingerCode = exec("loginctl", "enable-linger", System.getProperty("user.name"));
            if (lingerCode == 0) {
                ui.info("로그아웃 후에도 서비스가 유지됩니다 (enable-linger 적용).");
            } else {
                ui.warn("loginctl enable-linger 실패. 로그아웃 시 서비스가 중지될 수 있습니다.");
                ui.info("수동 실행: loginctl enable-linger " + System.getProperty("user.name"));
            }
        } else {
            ui.warn("서비스 파일은 생성했으나 enable/start에 문제가 있을 수 있습니다.");
            ui.info("수동 확인: systemctl --user status " + SERVICE_NAME);
        }
    }

    public static void uninstall(ConsoleUi ui) throws IOException, InterruptedException {
        if (!Files.exists(SERVICE_PATH)) {
            ui.warn("서비스가 등록되어 있지 않습니다.");
            return;
        }

        int stopCode = exec("systemctl", "--user", "stop", SERVICE_NAME);
        if (stopCode != 0) {
            ui.warn("서비스 중지에 문제가 있을 수 있습니다 (exit code: " + stopCode + ")");
        }

        int disableCode = exec("systemctl", "--user", "disable", SERVICE_NAME);
        if (disableCode != 0) {
            ui.warn("서비스 비활성화에 문제가 있을 수 있습니다 (exit code: " + disableCode + ")");
        }

        Files.deleteIfExists(SERVICE_PATH);
        exec("systemctl", "--user", "daemon-reload");

        ui.success("systemd 서비스 해제 완료");
    }

    private static int exec(String... command) throws IOException, InterruptedException {
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    private static Path resolveSelahHome() {
        String selahHome = System.getenv("SELAH_HOME");
        if (selahHome != null && !selahHome.isEmpty()) return Path.of(selahHome).toAbsolutePath();
        return Path.of("").toAbsolutePath();
    }
}
