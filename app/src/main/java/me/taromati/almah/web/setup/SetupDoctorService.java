package me.taromati.almah.web.setup;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ServiceInstaller;
import me.taromati.almah.web.setup.dto.*;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DoctorCheck의 로직을 웹 API용으로 래핑.
 * CLI DoctorCheck는 ConsoleUi에 직접 출력하므로, 결과를 구조화된 객체로 수집.
 */
@Service
@RequiredArgsConstructor
public class SetupDoctorService {

    private static final Path CONFIG_PATH = Path.of("config.yml");

    private final SetupValidationService validationService;

    /**
     * 전체 진단 실행.
     */
    public DoctorResult runDoctor() {
        List<CheckItem> items = new ArrayList<>();

        // 1. config.yml 존재
        items.add(checkConfigExists());

        ConfigGenerator config = ConfigGenerator.fromExistingConfig(CONFIG_PATH);

        // 2. Discord 검증
        items.addAll(checkDiscord(config));

        // 3. Telegram 검증
        items.addAll(checkTelegram(config));

        // 4. 메신저 1개 이상 확인
        items.add(checkMessengerRequired(config));

        // 5. LLM 연결
        items.add(checkLlmConnection(config));

        // 6. agent-data/ 디렉토리
        items.add(checkAgentDataDir(config));

        // 7. 서비스 등록 상태
        items.add(checkServiceStatus());

        int passed = (int) items.stream().filter(i -> i.status() == CheckStatus.PASS).count();
        int failed = (int) items.stream().filter(i -> i.status() == CheckStatus.FAIL).count();
        int warned = (int) items.stream().filter(i -> i.status() == CheckStatus.WARN).count();

        return new DoctorResult(items, passed, failed, warned);
    }

    private CheckItem checkConfigExists() {
        if (Files.exists(CONFIG_PATH)) {
            return new CheckItem("config", CheckStatus.PASS, "config.yml 존재");
        }
        return new CheckItem("config", CheckStatus.FAIL, "config.yml 미존재");
    }

    private List<CheckItem> checkDiscord(ConfigGenerator config) {
        List<CheckItem> items = new ArrayList<>();
        String token = config.getDiscordToken();
        if (token == null || token.isEmpty() || token.startsWith("YOUR_")) {
            // Discord 미설정 — 별도 항목 불필요 (메신저 필수에서 통합 검증)
            return items;
        }

        var result = validationService.validateDiscordToken(token);
        if (result.valid()) {
            items.add(new CheckItem("discord", CheckStatus.PASS, "Discord 토큰 유효 (" + result.botName() + ")"));
        } else {
            items.add(new CheckItem("discord", CheckStatus.FAIL, "Discord 토큰이 유효하지 않습니다"));
        }
        return items;
    }

    private List<CheckItem> checkTelegram(ConfigGenerator config) {
        List<CheckItem> items = new ArrayList<>();
        if (!config.getTelegramEnabled()) return items;

        String token = config.getTelegramToken();
        if (token == null || token.isEmpty() || token.startsWith("YOUR_")) {
            items.add(new CheckItem("telegram", CheckStatus.FAIL, "Telegram 토큰 미설정"));
            return items;
        }

        var result = validationService.validateTelegramToken(token);
        if (result.valid()) {
            items.add(new CheckItem("telegram", CheckStatus.PASS,
                    "Telegram 토큰 유효 (@" + result.username() + ")"));
        } else {
            items.add(new CheckItem("telegram", CheckStatus.FAIL,
                    "Telegram 토큰이 유효하지 않습니다"));
        }
        return items;
    }

    private CheckItem checkMessengerRequired(ConfigGenerator config) {
        if (config.hasAnyMessenger()) {
            return new CheckItem("messenger", CheckStatus.PASS, "메신저 설정됨");
        }
        return new CheckItem("messenger", CheckStatus.FAIL, "최소 1개의 메신저가 필요합니다");
    }

    private CheckItem checkLlmConnection(ConfigGenerator config) {
        String provider = config.getLlmProvider();
        if (provider == null) {
            return new CheckItem("llm", CheckStatus.WARN, "LLM 프로바이더 미설정");
        }
        var result = validationService.testLlmConnection(
                provider, config.getLlmApiKey(), config.getLlmBaseUrl(), config.getLlmCliPath());
        if (result.success()) {
            return new CheckItem("llm", CheckStatus.PASS,
                    provider.toUpperCase() + " 연결 OK (" + result.responseTimeMs() + "ms)");
        }
        return new CheckItem("llm", CheckStatus.FAIL, "LLM 연결 실패 (" + provider + ")");
    }

    private CheckItem checkAgentDataDir(ConfigGenerator config) {
        String dataDir = config.getAgentDataDir();
        if (dataDir == null || dataDir.isEmpty()) dataDir = "./agent-data/";
        Path path = Path.of(dataDir);
        if (Files.isDirectory(path)) {
            return new CheckItem("agent-data", CheckStatus.PASS,
                    "에이전트 데이터 디렉토리 존재: " + dataDir);
        }
        return new CheckItem("agent-data", CheckStatus.WARN,
                "에이전트 데이터 디렉토리 없음: " + dataDir);
    }

    CheckItem checkServiceStatus() {
        String os = ServiceInstaller.detectOs();
        try {
            return switch (os) {
                case "macos" -> {
                    var proc = new ProcessBuilder("launchctl", "list", "me.taromati.selah")
                            .redirectErrorStream(true).start();
                    int code = proc.waitFor();
                    yield code == 0
                            ? new CheckItem("service", CheckStatus.PASS, "LaunchAgent 서비스 등록됨")
                            : new CheckItem("service", CheckStatus.WARN, "서비스 미등록 (selah install로 등록 가능)");
                }
                case "linux" -> {
                    var proc = new ProcessBuilder("systemctl", "--user", "is-enabled", "selah")
                            .redirectErrorStream(true).start();
                    int code = proc.waitFor();
                    yield code == 0
                            ? new CheckItem("service", CheckStatus.PASS, "systemd 사용자 서비스 등록됨")
                            : new CheckItem("service", CheckStatus.WARN, "서비스 미등록 (selah install로 등록 가능)");
                }
                case "windows" -> {
                    var proc = new ProcessBuilder("schtasks", "/query", "/tn", "Selah")
                            .redirectErrorStream(true).start();
                    int code = proc.waitFor();
                    yield code == 0
                            ? new CheckItem("service", CheckStatus.PASS, "Windows 예약 작업 등록됨")
                            : new CheckItem("service", CheckStatus.WARN, "서비스 미등록 (selah install로 등록 가능)");
                }
                default -> new CheckItem("service", CheckStatus.WARN, "서비스 상태 확인 불가");
            };
        } catch (Exception e) {
            return new CheckItem("service", CheckStatus.WARN, "서비스 상태 확인 불가");
        }
    }
}
