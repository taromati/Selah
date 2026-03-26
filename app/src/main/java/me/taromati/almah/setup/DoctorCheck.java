package me.taromati.almah.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 설정 검증 유틸리티. config.yml 파싱 -> 각 항목 연결/존재 확인.
 */
public class DoctorCheck {

    private static final int DEFAULT_WEB_PORT = 6060;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void run() {
        ConsoleUi ui = new ConsoleUi();
        ui.section("Selah Doctor");

        int passed = 0;
        int failed = 0;
        int warned = 0;

        // 1. config.yml 존재
        Path configPath = Path.of("config.yml");
        if (Files.exists(configPath)) {
            ui.success("config.yml 존재");
            passed++;
        } else {
            ui.error("config.yml이 없습니다. 'selah setup'을 실행해주세요.");
            failed++;
            return;
        }

        // config 내용 읽기
        String configContent;
        try {
            configContent = Files.readString(configPath);
        } catch (IOException e) {
            ui.error("config.yml 읽기 실패: " + e.getMessage());
            return;
        }

        // 2. Discord 검증
        String discordEnabled = extractNestedValue(configContent, "discord", "enabled");
        String discordToken = extractNestedValue(configContent, "discord", "token");
        // discord.enabled가 명시적으로 "true"이거나, 기존 config(enabled 미포함)에서 토큰이 있으면 활성으로 간주
        boolean discordActive = "true".equals(discordEnabled)
                || (discordEnabled == null && discordToken != null && !discordToken.isEmpty());

        boolean hasValidDiscord = false;
        if (discordActive) {
            if (discordToken != null && !discordToken.isEmpty() && !discordToken.startsWith("YOUR_")) {
                ui.info("Discord 토큰 검증 중...");
                if (validateDiscordToken(discordToken)) {
                    ui.success("Discord 토큰 유효");
                    passed++;
                    hasValidDiscord = true;
                } else {
                    ui.error("Discord 토큰이 유효하지 않습니다.");
                    failed++;
                }
            } else {
                ui.warn("Discord 토큰이 설정되지 않았습니다.");
                warned++;
            }
        }

        // 3. Telegram 검증
        String telegramEnabled = extractNestedValue(configContent, "telegram", "enabled");
        String telegramToken = extractNestedValue(configContent, "telegram", "token");

        boolean hasValidTelegram = false;
        if ("true".equals(telegramEnabled)) {
            if (telegramToken != null && !telegramToken.isEmpty() && !telegramToken.startsWith("YOUR_")) {
                ui.info("Telegram 토큰 검증 중...");
                TelegramValidation result = validateTelegramToken(telegramToken);
                if (result.valid()) {
                    ui.success("Telegram 토큰 유효 (@" + result.username() + ")");
                    passed++;
                    hasValidTelegram = true;
                } else {
                    ui.error("Telegram 토큰이 유효하지 않습니다.");
                    failed++;
                }
            } else {
                ui.warn("Telegram이 활성화되어 있지만 토큰이 설정되지 않았습니다.");
                warned++;
            }

            // channel-mappings 확인
            String agentMapping = extractNestedValue(configContent, "telegram", "channel-mappings", "agent");
            if (agentMapping != null && !agentMapping.isEmpty()) {
                ui.success("Telegram channel-mappings 설정됨 (agent: " + agentMapping + ")");
                passed++;
            } else {
                ui.warn("Telegram channel-mappings에 agent 매핑이 없습니다.");
                warned++;
            }
        }

        // 메신저 하나 이상 설정 확인
        if (!hasValidDiscord && !hasValidTelegram) {
            // 토큰 placeholder/미설정인 경우도 체크
            boolean discordPlaceholder = discordToken != null && !discordToken.isEmpty()
                    && !discordToken.startsWith("YOUR_");
            boolean telegramPlaceholder = "true".equals(telegramEnabled) && telegramToken != null
                    && !telegramToken.isEmpty() && !telegramToken.startsWith("YOUR_");
            if (!discordPlaceholder && !telegramPlaceholder) {
                ui.error("메신저가 설정되지 않았습니다. Discord 또는 Telegram 중 하나는 설정이 필요합니다.");
                ui.info("  → 'selah setup'을 실행하여 메신저를 설정해주세요.");
                failed++;
            }
        }

        // 4. LLM 연결 확인 — 프로바이더 타입별 검증
        boolean llmChecked = false;
        Map<String, Object> providers = YamlHelper.getMap(
                new org.yaml.snakeyaml.Yaml().load(configContent), "llm", "providers");
        for (var entry : providers.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> providerMap)) continue;
            Object enabled = providerMap.get("enabled");
            if (!Boolean.TRUE.equals(enabled) && !"true".equals(enabled)) continue;

            String providerName = entry.getKey();
            String type = providerMap.get("type") != null ? providerMap.get("type").toString() : providerName;

            switch (type) {
                case "openai" -> {
                    String apiKey = providerMap.get("api-key") != null ? providerMap.get("api-key").toString() : "";
                    if (!apiKey.isEmpty() && !apiKey.startsWith("YOUR_")) {
                        ui.info("OpenAI API 연결 확인 중...");
                        if (testHttpEndpoint("https://api.openai.com/v1/models", "Bearer " + apiKey)) {
                            ui.success("OpenAI API 연결 OK (" + providerName + ")");
                            passed++;
                        } else {
                            ui.error("OpenAI API 연결 실패 (" + providerName + ")");
                            failed++;
                        }
                    }
                }
                case "openai-codex" -> {
                    // Codex는 브라우저 인증 기반 — API 키 검증 불가, 설정 존재만 확인
                    ui.success("LLM 프로바이더 설정됨: " + providerName + " (" + type + ")");
                    passed++;
                }
                case "vllm" -> {
                    String baseUrl = providerMap.get("base-url") != null ? providerMap.get("base-url").toString() : "";
                    if (!baseUrl.isEmpty()) {
                        ui.info("vLLM 서버 연결 확인 중...");
                        if (testHttpEndpoint(baseUrl.replace("/v1", "/v1/models"), null)) {
                            ui.success("vLLM 서버 연결 OK (" + providerName + ")");
                            passed++;
                        } else {
                            ui.error("vLLM 서버에 연결할 수 없습니다: " + baseUrl);
                            failed++;
                        }
                    }
                }
                case "gemini-cli" -> {
                    ui.success("LLM 프로바이더 설정됨: " + providerName + " (" + type + ")");
                    passed++;
                }
                default -> {
                    ui.success("LLM 프로바이더 설정됨: " + providerName + " (" + type + ")");
                    passed++;
                }
            }
            llmChecked = true;
        }
        if (!llmChecked) {
            ui.warn("LLM 프로바이더가 설정되지 않았습니다.");
            warned++;
        }

        // 5. agent-data/ 확인
        String dataDir = extractNestedValue(configContent, "plugins", "agent", "data-dir");
        if (dataDir == null || dataDir.isEmpty()) dataDir = "./agent-data/";
        Path agentDataPath = Path.of(dataDir);
        if (Files.isDirectory(agentDataPath)) {
            ui.success("에이전트 데이터 디렉토리 존재: " + dataDir);
            passed++;
        } else {
            ui.warn("에이전트 데이터 디렉토리가 없습니다: " + dataDir);
            warned++;
        }

        // 6. 포트 / 실행 상태 확인
        if (isPortAvailable(DEFAULT_WEB_PORT)) {
            ui.success("웹 포트 " + DEFAULT_WEB_PORT + " 사용 가능 (Selah 미실행)");
            passed++;
        } else {
            // 포트 사용 중 — Selah인지 확인
            if (testHttpEndpoint("http://localhost:" + DEFAULT_WEB_PORT + "/api/system/health", null)) {
                ui.success("Selah가 실행 중입니다 (포트 " + DEFAULT_WEB_PORT + ")");
                passed++;
            } else {
                ui.error("웹 포트 " + DEFAULT_WEB_PORT + "이 다른 프로세스에 의해 사용 중입니다.");
                failed++;
            }
        }

        // 7. 서비스 상태
        checkServiceStatus(ui);

        // 결과 요약
        ui.section("결과");
        ui.info("통과: " + passed + " / 실패: " + failed + " / 경고: " + warned);
        if (failed == 0) {
            ui.success("모든 필수 검증을 통과했습니다!");
        } else {
            ui.error(failed + "개의 문제가 발견되었습니다. 위의 오류를 확인해주세요.");
        }
    }

    /**
     * 가변 깊이 중첩 YAML 값을 추출한다.
     * 예: extractNestedValue(content, "telegram", "channel-mappings", "agent")
     *     -> telegram: > channel-mappings: > agent: 의 값 반환
     *
     * 예: extractNestedValue(content, "llm", "providers", "openai", "api-key")
     *     -> llm: > providers: > openai: > api-key: 의 값 반환
     *
     * indent 기반으로 섹션 경계를 판별하므로 ConfigGenerator가 생성한
     * 정규 형식의 YAML에서 정확히 동작한다.
     */
    static String extractNestedValue(String content, String... keys) {
        if (keys.length == 0) return null;

        String[] lines = content.split("\n");
        int lineIdx = 0;
        int currentIndent = -1;

        // keys[0..N-2]는 섹션 키 — 순서대로 진입
        for (int depth = 0; depth < keys.length - 1; depth++) {
            String sectionKey = keys[depth];
            boolean found = false;
            while (lineIdx < lines.length) {
                String line = lines[lineIdx];
                String trimmed = line.trim();
                int indent = line.length() - line.stripLeading().length();
                lineIdx++;

                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                // 현재 깊이의 섹션에서 벗어났으면 실패
                if (currentIndent >= 0 && indent <= currentIndent) return null;

                if (trimmed.equals(sectionKey + ":") || trimmed.startsWith(sectionKey + ": ")) {
                    currentIndent = indent;
                    found = true;
                    break;
                }
            }
            if (!found) return null;
        }

        // keys[N-1]은 값 키 — 해당 섹션 안에서 탐색
        String valueKey = keys[keys.length - 1];

        // keys가 1개인 경우 (top-level 값) — currentIndent는 -1
        // 이 때는 모든 라인에서 해당 키를 탐색
        if (keys.length == 1) {
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith(valueKey + ":")) {
                    String value = trimmed.substring(valueKey.length() + 1).trim();
                    return unquote(value);
                }
            }
            return null;
        }

        while (lineIdx < lines.length) {
            String line = lines[lineIdx];
            String trimmed = line.trim();
            int indent = line.length() - line.stripLeading().length();
            lineIdx++;

            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            // 섹션 종료: indent가 상위 섹션 이하
            if (indent <= currentIndent) return null;

            if (trimmed.startsWith(valueKey + ":")) {
                String value = trimmed.substring(valueKey.length() + 1).trim();
                return unquote(value);
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean validateDiscordToken(String token) {
        return testHttpEndpoint("https://discord.com/api/v10/users/@me", "Bot " + token);
    }

    private static TelegramValidation validateTelegramToken(String token) {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/getMe"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode result = MAPPER.readTree(response.body()).path("result");
                String username = result.path("username").asText(null);
                return new TelegramValidation(true, username);
            }
        } catch (Exception ignored) {}
        return new TelegramValidation(false, null);
    }

    private static boolean testHttpEndpoint(String url, String authorization) {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10));
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void checkServiceStatus(ConsoleUi ui) {
        String os = ServiceInstaller.detectOs();
        try {
            switch (os) {
                case "macos" -> {
                    var proc = new ProcessBuilder("launchctl", "list", "me.taromati.selah")
                            .redirectErrorStream(true).start();
                    int code = proc.waitFor();
                    if (code == 0) {
                        ui.success("LaunchAgent 서비스 등록됨");
                    } else {
                        ui.info("LaunchAgent 서비스가 등록되어 있지 않습니다. (selah enable로 등록 가능)");
                    }
                }
                case "linux" -> {
                    var proc = new ProcessBuilder("systemctl", "--user", "is-enabled", "selah")
                            .redirectErrorStream(true).start();
                    int code = proc.waitFor();
                    if (code == 0) {
                        ui.success("systemd 사용자 서비스 등록됨");
                    } else {
                        ui.info("systemd 서비스가 등록되어 있지 않습니다. (selah enable로 등록 가능)");
                    }
                }
                case "windows" -> {
                    var proc = new ProcessBuilder("schtasks", "/query", "/tn", "Selah")
                            .redirectErrorStream(true).start();
                    int code = proc.waitFor();
                    if (code == 0) {
                        ui.success("Windows 예약 작업 등록됨");
                    } else {
                        ui.info("Windows 예약 작업이 등록되어 있지 않습니다. (selah enable로 등록 가능)");
                    }
                }
            }
        } catch (Exception e) {
            ui.info("서비스 상태를 확인할 수 없습니다.");
        }
    }

    private record TelegramValidation(boolean valid, String username) {}
}
