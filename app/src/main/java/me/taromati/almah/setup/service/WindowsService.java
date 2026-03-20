package me.taromati.almah.setup.service;

import me.taromati.almah.setup.ConsoleUi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class WindowsService {

    private static final String TASK_NAME = "Selah";

    public static void install(ConsoleUi ui) throws IOException, InterruptedException {
        Path selahHome = resolveSelahHome();
        Path wrapper = selahHome.resolve("bin/selah.bat");
        if (!Files.exists(wrapper)) {
            throw new IllegalStateException("래퍼 스크립트를 찾을 수 없습니다: " + wrapper);
        }

        Files.createDirectories(selahHome.resolve("logs"));

        // 기존 작업 있으면 먼저 삭제
        exec("schtasks", "/delete", "/tn", TASK_NAME, "/f");

        // Startup 폴더에 바로가기 생성 (관리자 권한 불필요, schtasks /sc onlogon 대체)
        String startupDir = System.getenv("APPDATA") + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
        Path shortcutVbs = selahHome.resolve("create-shortcut.vbs");
        Path shortcutPath = Path.of(startupDir, "Selah.lnk");

        String vbsScript = """
                Set WshShell = CreateObject("WScript.Shell")
                Set lnk = WshShell.CreateShortcut("%s")
                lnk.TargetPath = "%s"
                lnk.WorkingDirectory = "%s"
                lnk.Description = "Selah AI Agent Bot"
                lnk.Save
                """.formatted(shortcutPath, wrapper, selahHome);

        Files.writeString(shortcutVbs, vbsScript);
        int exitCode = exec("cscript", "//nologo", shortcutVbs.toString());
        Files.deleteIfExists(shortcutVbs);

        if (exitCode == 0 && Files.exists(shortcutPath)) {
            ui.success("시작 프로그램 등록 완료: " + shortcutPath);
            ui.info("로그인 시 자동 시작됩니다.");

            if (ui.confirm("지금 바로 시작하시겠습니까?")) {
                new ProcessBuilder(wrapper.toString()).directory(selahHome.toFile()).start();
            }
        } else {
            // Startup 폴더 실패 시 schtasks 폴백 (관리자 권한 필요)
            ui.warn("시작 프로그램 등록 실패. schtasks로 시도합니다 (관리자 권한 필요).");
            String command = "\"%s\"".formatted(wrapper);
            int taskCode = exec("schtasks", "/create", "/tn", TASK_NAME,
                    "/tr", command, "/sc", "onlogon", "/rl", "limited", "/f");

            if (taskCode == 0) {
                ui.success("Windows 예약 작업 등록 완료: " + TASK_NAME);
                if (ui.confirm("지금 바로 시작하시겠습니까?")) {
                    exec("schtasks", "/run", "/tn", TASK_NAME);
                }
            } else {
                ui.error("서비스 등록 실패. 관리자 권한으로 다시 시도해주세요.");
            }
        }
    }

    public static void uninstall(ConsoleUi ui) throws IOException, InterruptedException {
        boolean cleaned = false;

        // 실행 중인 프로세스 종료
        int killCode = exec("taskkill", "/f", "/im", "java.exe", "/fi",
                "WINDOWTITLE eq Selah*");
        // taskkill 실패는 무시 (프로세스가 없을 수 있음)

        // Startup 폴더 바로가기 삭제
        String startupDir = System.getenv("APPDATA") + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
        Path shortcutPath = Path.of(startupDir, "Selah.lnk");
        if (Files.deleteIfExists(shortcutPath)) {
            ui.success("시작 프로그램 해제 완료");
            cleaned = true;
        }

        // schtasks 삭제
        int exitCode = exec("schtasks", "/delete", "/tn", TASK_NAME, "/f");
        if (exitCode == 0) {
            ui.success("Windows 예약 작업 해제 완료");
            cleaned = true;
        }

        if (!cleaned) {
            ui.warn("등록된 서비스가 없습니다.");
        }
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
