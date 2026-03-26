package me.taromati.almah.setup.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ConsoleUi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class TelegramTokenStep {

    private static final String DEFAULT_BASE_URL = "https://api.telegram.org";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void run(ConsoleUi ui, ConfigGenerator config) {
        run(ui, config, DEFAULT_BASE_URL);
    }

    static void run(ConsoleUi ui, ConfigGenerator config, String baseUrl) {
        ui.info("Telegram 봇이 필요합니다. 아직 없다면 아래 절차를 따라주세요:");
        ui.info("  1. Telegram에서 @BotFather를 검색하여 대화를 시작합니다.");
        ui.info("  2. /newbot 명령을 보냅니다.");
        ui.info("  3. 봇 이름과 사용자명을 입력합니다.");
        ui.info("  4. 발급된 토큰을 아래에 붙여넣습니다.");

        String token;
        TelegramBotInfo botInfo;

        while (true) {
            token = ui.promptSecret("봇 토큰");
            if (token.isEmpty()) {
                ui.warn("토큰을 건너뜁니다. 나중에 config.yml에서 직접 설정할 수 있습니다.");
                config.telegramEnabled(true);
                config.telegramToken("YOUR_TELEGRAM_BOT_TOKEN");
                return;
            }

            ui.info("토큰 검증 중...");
            botInfo = validateToken(token, baseUrl);
            if (botInfo != null) {
                ui.success("토큰 검증 완료! (봇 이름: " + botInfo.firstName() + ", @" + botInfo.username() + ")");
                config.telegramEnabled(true);
                config.telegramToken(token);
                config.telegramBotUsername(botInfo.username());
                break;
            } else {
                ui.error("토큰이 유효하지 않습니다. 다시 입력해주세요.");
            }
        }

        // Chat ID 설정
        setupChatId(ui, config, token, botInfo.username(), baseUrl);
    }

    private static void setupChatId(ConsoleUi ui, ConfigGenerator config,
                                     String token, String botUsername, String baseUrl) {
        ui.info("Agent 채널의 Chat ID를 설정합니다.");

        int method = ui.choose("방법을 선택하세요:", List.of(
                "자동 감지 (봇에 메시지를 보내면 자동 인식)",
                "직접 입력",
                "나중에 설정"
        ));

        String agentChatId = null;

        if (method == 1) {
            // 자동 감지
            agentChatId = detectChatId(ui, token, botUsername, baseUrl);
            if (agentChatId == null) {
                // 타임아웃 — 직접 입력 또는 나중에 전환
                int fallback = ui.choose("Chat ID를 어떻게 설정하시겠습니까?", List.of(
                        "직접 입력",
                        "나중에 설정"
                ));
                if (fallback == 1) {
                    agentChatId = ui.prompt("Chat ID");
                }
            }
        } else if (method == 2) {
            // 직접 입력
            agentChatId = ui.prompt("Chat ID");
        }
        // method == 3: 나중에 설정 — agentChatId는 null

        if (agentChatId != null && !agentChatId.isEmpty()) {
            config.telegramChannelMapping("agent", agentChatId);
            ui.success("Chat ID 설정 완료!");
        } else {
            ui.warn("Chat ID를 건너뜁니다. 나중에 config.yml에서 직접 설정하거나 웹 UI에서 설정할 수 있습니다.");
        }
    }

    static TelegramBotInfo validateToken(String token, String baseUrl) {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/bot" + token + "/getMe"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            JsonNode result = MAPPER.readTree(response.body()).path("result");
            String username = result.path("username").asText(null);
            String firstName = result.path("first_name").asText(null);
            if (username == null) return null;
            return new TelegramBotInfo(username, firstName);
        } catch (Exception e) {
            return null;
        }
    }

    static String detectChatId(ConsoleUi ui, String token, String botUsername, String baseUrl) {
        ui.info("봇(@" + botUsername + ")에게 아무 메시지를 보내주세요. (그룹에서 사용하려면 그룹에 봇을 추가 후 메시지)");
        ui.info("대기 중... (Ctrl+C로 취소)");

        try (var client = HttpClient.newHttpClient()) {
            // webhook 제거
            deleteWebhook(client, token, baseUrl);

            // 기존 업데이트 소거 → 다음 poll에 사용할 offset 반환
            long nextOffset = clearUpdates(client, token, baseUrl);

            for (int attempt = 0; attempt < 10; attempt++) {
                var uri = URI.create(baseUrl + "/bot" + token
                        + "/getUpdates?timeout=30&offset=" + nextOffset
                        + "&allowed_updates=%5B%22message%22%5D");
                var request = HttpRequest.newBuilder()
                        .uri(uri)
                        .GET()
                        .timeout(Duration.ofSeconds(35))
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode updates = MAPPER.readTree(response.body()).path("result");
                    if (updates.isArray() && !updates.isEmpty()) {
                        JsonNode first = updates.get(0);
                        // 다음 poll을 위해 offset 갱신
                        nextOffset = first.path("update_id").asLong() + 1;

                        JsonNode chat = first.path("message").path("chat");
                        String chatId = String.valueOf(chat.path("id").asLong());
                        String chatTitle = chat.has("title")
                                ? chat.path("title").asText()
                                : chat.path("first_name").asText(null);
                        ui.success("메시지 감지! Chat ID: " + chatId
                                + (chatTitle != null ? " (" + chatTitle + ")" : ""));

                        if (ui.confirm("이 채팅을 Agent 채널로 사용하시겠습니까?")) {
                            return chatId;
                        } else {
                            // 거부 시 null 반환하여 직접 입력/나중에 전환
                            return null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            ui.warn("감지 중 오류: " + e.getMessage());
        }

        ui.warn("메시지를 감지하지 못했습니다.");
        return null;
    }

    private static void deleteWebhook(HttpClient client, String token, String baseUrl) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/bot" + token + "/deleteWebhook"))
                    .GET()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }

    /**
     * 기존 업데이트를 소거하고 다음 poll에 사용할 offset을 반환한다.
     */
    private static long clearUpdates(HttpClient client, String token, String baseUrl) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/bot" + token + "/getUpdates?offset=-1"))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode updates = MAPPER.readTree(response.body()).path("result");
            if (updates.isArray() && !updates.isEmpty()) {
                long lastUpdateId = updates.get(updates.size() - 1).path("update_id").asLong();
                // 마지막 업데이트 확인 처리
                client.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/bot" + token
                                + "/getUpdates?offset=" + (lastUpdateId + 1)))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                return lastUpdateId + 1;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    record TelegramBotInfo(String username, String firstName) {}
}
