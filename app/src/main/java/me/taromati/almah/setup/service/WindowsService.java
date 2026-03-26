package me.taromati.almah.setup.service;

import me.taromati.almah.setup.ConsoleUi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class WindowsService {

    private static final String SERVICE_NAME = "selah";
    private static final String SEARXNG_SERVICE_NAME = "selah-searxng";

    public static void install(ConsoleUi ui) throws IOException, InterruptedException {
        Path selahHome = resolveSelahHome();
        Path nssm = selahHome.resolve("bin/nssm.exe");

        if (!Files.exists(nssm)) {
            throw new IllegalStateException(
                    "NSSM을 찾을 수 없습니다: " + nssm + "\n재설치하거나 'selah update'를 실행해주세요.");
        }

        if (!isAdmin()) {
            ui.error("서비스 등록에는 관리자 권한이 필요합니다.");
            ui.info("명령 프롬프트를 '관리자 권한으로 실행'한 후 다시 시도해주세요.");
            return;
        }

        Files.createDirectories(selahHome.resolve("logs"));

        boolean alreadyRunning = me.taromati.almah.setup.ServiceInstaller.isSelahRunning();

        // 기존 서비스 정리 (레거시 + NSSM)
        cleanupLegacy(ui);
        exec(nssm.toString(), "stop", SERVICE_NAME);
        exec(nssm.toString(), "remove", SERVICE_NAME, "confirm");

        // Selah 서비스 등록
        Path java = selahHome.resolve("runtime/bin/java.exe");
        Path jar = findJar(selahHome);

        if (!Files.exists(java)) {
            throw new IllegalStateException("런타임을 찾을 수 없습니다: " + java);
        }
        if (jar == null) {
            throw new IllegalStateException("JAR 파일을 찾을 수 없습니다.");
        }

        // 사용자 계정으로 실행 (홈 디렉토리 접근 필요 — Codex auth 등)
        String username = System.getProperty("user.name");
        String password = ui.promptSecret("Windows 비밀번호 (" + username + ")");

        String nssmPath = nssm.toString();
        exec(nssmPath, "install", SERVICE_NAME, java.toString());
        exec(nssmPath, "set", SERVICE_NAME, "AppParameters", "-jar " + jar + " _server");
        exec(nssmPath, "set", SERVICE_NAME, "AppDirectory", selahHome.toString());
        exec(nssmPath, "set", SERVICE_NAME, "AppEnvironmentExtra", "SELAH_HOME=" + selahHome);
        exec(nssmPath, "set", SERVICE_NAME, "DisplayName", "Selah");
        exec(nssmPath, "set", SERVICE_NAME, "Description", "Selah AI Agent Bot");
        exec(nssmPath, "set", SERVICE_NAME, "ObjectName", ".\\" + username, password);
        exec(nssmPath, "set", SERVICE_NAME, "AppExit", "Default", "Restart");
        exec(nssmPath, "set", SERVICE_NAME, "AppRestartDelay", "10000");
        exec(nssmPath, "set", SERVICE_NAME, "Start", "SERVICE_AUTO_START");

        ui.success("Selah 서비스 등록 완료");

        // SearXNG 서비스도 등록 (설치돼있으면)
        installSearxng(ui, selahHome, nssmPath);

        // 서비스 시작
        if (alreadyRunning) {
            ui.info("Selah가 이미 실행 중입니다.");
        } else {
            exec(nssmPath, "start", SERVICE_NAME);
            // health check로 시작 검증 (최대 15초 대기)
            ui.info("서비스 시작 확인 중...");
            boolean started = false;
            for (int i = 0; i < 5; i++) {
                Thread.sleep(3000);
                if (me.taromati.almah.setup.ServiceInstaller.isSelahRunning()) {
                    started = true;
                    break;
                }
            }
            if (started) {
                ui.success("Selah 서비스 시작 확인 완료");
            } else {
                ui.warn("서비스가 시작되었으나 아직 응답하지 않습니다. 로그를 확인해주세요:");
                ui.info("  " + selahHome.resolve("logs/nssm-stderr.log"));
            }
        }
    }

    private static void installSearxng(ConsoleUi ui, Path selahHome, String nssmPath)
            throws IOException, InterruptedException {
        Path searxngRun = selahHome.resolve("searxng/.venv/Scripts/searxng-run.exe");
        if (!Files.exists(searxngRun)) {
            ui.info("SearXNG가 설치되어 있지 않습니다. (웹 검색 비활성화)");
            return;
        }

        // 기존 서비스 정리
        exec(nssmPath, "stop", SEARXNG_SERVICE_NAME);
        exec(nssmPath, "remove", SEARXNG_SERVICE_NAME, "confirm");

        Path settings = selahHome.resolve("searxng/settings.yml");
        Path searxngDir = selahHome.resolve("searxng");

        exec(nssmPath, "install", SEARXNG_SERVICE_NAME, searxngRun.toString());
        exec(nssmPath, "set", SEARXNG_SERVICE_NAME, "AppDirectory", searxngDir.toString());
        exec(nssmPath, "set", SEARXNG_SERVICE_NAME, "AppEnvironmentExtra",
                "SEARXNG_SETTINGS_PATH=" + settings);
        exec(nssmPath, "set", SEARXNG_SERVICE_NAME, "DisplayName", "Selah SearXNG");
        exec(nssmPath, "set", SEARXNG_SERVICE_NAME, "Description",
                "SearXNG meta-search engine for Selah");
        exec(nssmPath, "set", SEARXNG_SERVICE_NAME, "AppExit", "Default", "Restart");
        exec(nssmPath, "set", SEARXNG_SERVICE_NAME, "AppRestartDelay", "5000");
        exec(nssmPath, "set", SEARXNG_SERVICE_NAME, "Start", "SERVICE_AUTO_START");

        exec(nssmPath, "start", SEARXNG_SERVICE_NAME);
        ui.success("SearXNG 서비스 등록 및 시작 완료");
    }

    public static void uninstall(ConsoleUi ui) throws IOException, InterruptedException {
        Path selahHome = resolveSelahHome();
        Path nssm = selahHome.resolve("bin/nssm.exe");

        // 레거시 항목 항상 정리
        cleanupLegacy(ui);

        if (!Files.exists(nssm)) {
            ui.warn("NSSM을 찾을 수 없습니다. 서비스 해제를 건너뜁니다.");
            return;
        }

        if (!isAdmin()) {
            ui.error("서비스 해제에는 관리자 권한이 필요합니다.");
            ui.info("명령 프롬프트를 '관리자 권한으로 실행'한 후 다시 시도해주세요.");
            return;
        }

        boolean cleaned = false;
        String nssmPath = nssm.toString();

        // SearXNG 서비스 해제
        if (isNssmServiceInstalled(nssmPath, SEARXNG_SERVICE_NAME)) {
            exec(nssmPath, "stop", SEARXNG_SERVICE_NAME);
            exec(nssmPath, "remove", SEARXNG_SERVICE_NAME, "confirm");
            ui.success("SearXNG 서비스 해제 완료");
            cleaned = true;
        }

        // Selah 서비스 해제
        if (isNssmServiceInstalled(nssmPath, SERVICE_NAME)) {
            exec(nssmPath, "stop", SERVICE_NAME);
            exec(nssmPath, "remove", SERVICE_NAME, "confirm");
            ui.success("Selah 서비스 해제 완료");
            cleaned = true;
        }

        if (!cleaned) {
            ui.warn("등록된 서비스가 없습니다.");
        } else {
            // 종료 검증
            Thread.sleep(2000);
            if (me.taromati.almah.setup.ServiceInstaller.isSelahRunning()) {
                ui.warn("프로세스가 아직 응답합니다. 완전히 종료되지 않았을 수 있습니다.");
            } else {
                ui.success("프로세스 종료 확인");
            }
        }
    }

    /** NSSM으로 서비스가 등록되어 있는지 확인 */
    public static boolean isNssmServiceInstalled(String nssmPath, String serviceName) {
        try {
            Process p = new ProcessBuilder(nssmPath, "status", serviceName)
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            // "SERVICE_STOPPED", "SERVICE_RUNNING" 등이면 등록됨
            // "Can't open service" 등이면 미등록
            return output.startsWith("SERVICE_");
        } catch (Exception e) {
            return false;
        }
    }

    /** Selah NSSM 서비스 등록 여부 확인 (ServiceRestarter, DoctorCheck에서 사용) */
    public static boolean isServiceRegistered() {
        Path selahHome = resolveSelahHome();
        Path nssm = selahHome.resolve("bin/nssm.exe");
        if (!Files.exists(nssm)) return false;
        return isNssmServiceInstalled(nssm.toString(), SERVICE_NAME);
    }

    /** SearXNG NSSM 서비스 시작 시도 (2차 방어용) */
    public static boolean tryStartSearxng() {
        Path selahHome = resolveSelahHome();
        Path nssm = selahHome.resolve("bin/nssm.exe");
        if (!Files.exists(nssm)) return false;
        try {
            int code = exec(nssm.toString(), "start", SEARXNG_SERVICE_NAME);
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupLegacy(ConsoleUi ui) throws IOException, InterruptedException {
        // Startup 폴더 바로가기 삭제
        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            Path shortcutPath = Path.of(appdata,
                    "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "Selah.lnk");
            if (Files.deleteIfExists(shortcutPath)) {
                ui.info("레거시 시작 프로그램 항목 제거");
            }
        }
        // schtasks 삭제
        exec("schtasks", "/delete", "/tn", "Selah", "/f");
    }

    private static boolean isAdmin() {
        try {
            Process p = new ProcessBuilder("net", "session")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path findJar(Path selahHome) throws IOException {
        Path libDir = selahHome.resolve("lib");
        if (!Files.isDirectory(libDir)) return null;
        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("selah-.*\\.jar"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    private static int exec(String... command) throws IOException, InterruptedException {
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
                .waitFor();
    }

    public static Path resolveSelahHome() {
        String selahHome = System.getenv("SELAH_HOME");
        if (selahHome != null && !selahHome.isEmpty()) return Path.of(selahHome).toAbsolutePath();
        return Path.of("").toAbsolutePath();
    }
}
