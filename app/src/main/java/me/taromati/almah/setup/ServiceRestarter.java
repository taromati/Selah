package me.taromati.almah.setup;

import me.taromati.almah.setup.service.WindowsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 서비스 재시작 유틸리티. CLI(selah restart)와 웹 UI(재시작 버튼)에서 공용.
 */
public class ServiceRestarter {

    public record Result(boolean success, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    /**
     * 서비스 등록 여부를 확인하고, 등록되어 있으면 재시작한다.
     * 미등록이면 enable 안내 메시지를 반환한다.
     *
     * @param async true면 200ms 후 비동기 실행 (웹 응답 반환 후), false면 동기 실행 (CLI)
     */
    public static Result restart(boolean async) {
        String os = ServiceInstaller.detectOs();

        if (!isServiceRegistered(os)) {
            return Result.fail("서비스가 등록되어 있지 않습니다. 먼저 'selah start'을 실행해주세요.");
        }

        if (async) {
            Thread.startVirtualThread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                doRestart(os);
            });
            return Result.ok("재시작 중...");
        } else {
            return doRestart(os);
        }
    }

    private static Result doRestart(String os) {
        try {
            return switch (os) {
                case "macos" -> {
                    String uid = getUid();
                    var proc = new ProcessBuilder("launchctl", "kickstart", "-k",
                            "gui/" + uid + "/me.taromati.selah").inheritIO().start();
                    int code = proc.waitFor();
                    yield code == 0
                            ? Result.ok("재시작 완료")
                            : Result.fail("재시작 실패 (exit code: " + code + ")");
                }
                case "linux" -> {
                    var proc = new ProcessBuilder("systemctl", "--user", "restart", "selah")
                            .inheritIO().start();
                    int code = proc.waitFor();
                    yield code == 0
                            ? Result.ok("재시작 완료")
                            : Result.fail("재시작 실패 (exit code: " + code + ")");
                }
                case "windows" -> {
                    // NSSM 서비스면 nssm restart, 아니면 레거시 방식
                    if (WindowsService.isServiceRegistered()) {
                        Path nssm = WindowsService.resolveSelahHome().resolve("bin/nssm.exe");
                        var proc = new ProcessBuilder(nssm.toString(), "restart", "selah")
                                .inheritIO().start();
                        int code = proc.waitFor();
                        yield code == 0
                                ? Result.ok("재시작 완료")
                                : Result.fail("재시작 실패 (exit code: " + code + ")");
                    }
                    // 레거시: 재시작 스크립트 생성 후 프로세스 종료
                    String selahHome = System.getenv("SELAH_HOME");
                    if (selahHome == null || selahHome.isEmpty()) {
                        selahHome = System.getProperty("user.dir");
                    }
                    Path wrapper = Path.of(selahHome, "bin", "selah.bat");
                    Path restartScript = Path.of(selahHome, "logs", "restart.bat");
                    Files.createDirectories(restartScript.getParent());
                    Files.writeString(restartScript,
                            "@echo off\r\ntimeout /t 3 /nobreak >nul\r\nstart \"Selah\" \"%s\"\r\ndel \"%%~f0\"\r\n"
                                    .formatted(wrapper));
                    new ProcessBuilder("cmd", "/c", "start", "/min", "", restartScript.toString())
                            .start();
                    System.exit(0);
                    yield Result.ok("재시작 중...");
                }
                default -> Result.fail("이 OS에서는 'selah start' 후 웹 UI의 재시작 버튼을 사용해주세요.");
            };
        } catch (Exception e) {
            return Result.fail("재시작 실패: " + e.getMessage());
        }
    }

    private static boolean isServiceRegistered(String os) {
        try {
            return switch (os) {
                case "macos" -> {
                    var proc = new ProcessBuilder("launchctl", "list", "me.taromati.selah")
                            .redirectErrorStream(true).start();
                    yield proc.waitFor() == 0;
                }
                case "linux" -> {
                    var proc = new ProcessBuilder("systemctl", "--user", "is-enabled", "selah")
                            .redirectErrorStream(true).start();
                    yield proc.waitFor() == 0;
                }
                case "windows" -> {
                    yield WindowsService.isServiceRegistered();
                }
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    private static String getUid() throws IOException {
        var proc = new ProcessBuilder("id", "-u").start();
        return new String(proc.getInputStream().readAllBytes()).trim();
    }
}
