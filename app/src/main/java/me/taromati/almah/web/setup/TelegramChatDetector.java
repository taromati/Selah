package me.taromati.almah.web.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.telegram.TelegramMessengerGateway;
import me.taromati.almah.web.setup.dto.ChatDetectionStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Telegram Chat ID 자동 감지의 서버 측 상태 관리.
 * 웹 요청 -> long polling 시작 -> 프론트엔드 폴링으로 결과 전달.
 */
@Slf4j
@Component
public class TelegramChatDetector {

    private static final String TELEGRAM_API = "https://api.telegram.org";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile DetectionSession activeSession;

    private final ObjectProvider<TelegramMessengerGateway> telegramGatewayProvider;

    public TelegramChatDetector(ObjectProvider<TelegramMessengerGateway> telegramGatewayProvider) {
        this.telegramGatewayProvider = telegramGatewayProvider;
    }

    /**
     * 새 감지 세션 시작. 이전 세션은 취소.
     * 별도 가상 스레드에서 Telegram long polling 실행.
     *
     * @throws IllegalStateException 봇이 이미 활성(connected) 상태인 경우
     */
    public void startDetection(String token) {
        // 봇 활성 상태 가드
        TelegramMessengerGateway gateway = telegramGatewayProvider.getIfAvailable();
        if (gateway != null) {
            throw new IllegalStateException("봇이 이미 활성 상태입니다. Chat ID를 직접 입력하세요.");
        }

        if (activeSession != null) {
            activeSession.cancel();
        }
        activeSession = new DetectionSession(token);
        Thread.startVirtualThread(() -> activeSession.run());
    }

    /**
     * 현재 감지 상태 조회. 프론트엔드 폴링용.
     */
    public ChatDetectionStatus getStatus() {
        if (activeSession == null) {
            return ChatDetectionStatus.idle();
        }
        return activeSession.getStatus();
    }

    private static class DetectionSession {
        private static final org.slf4j.Logger sessionLog =
                org.slf4j.LoggerFactory.getLogger(DetectionSession.class);
        private volatile ChatDetectionStatus status = ChatDetectionStatus.waiting();
        private volatile boolean cancelled = false;
        private final String token;

        DetectionSession(String token) {
            this.token = token;
        }

        void run() {
            try (var client = HttpClient.newHttpClient()) {
                // 1. deleteWebhook
                deleteWebhook(client);

                // 2. clearUpdates
                clearUpdates(client);

                // 3. 4회 long polling (각 30초) = 최대 120초
                for (int attempt = 0; attempt < 4; attempt++) {
                    if (cancelled) return;

                    var request = HttpRequest.newBuilder()
                            .uri(URI.create(TELEGRAM_API + "/bot" + token
                                    + "/getUpdates?timeout=30&allowed_updates=[\"message\"]"))
                            .GET()
                            .timeout(Duration.ofSeconds(35))
                            .build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        JsonNode updates = MAPPER.readTree(response.body()).path("result");
                        if (updates.isArray() && !updates.isEmpty()) {
                            JsonNode chat = updates.get(0).path("message").path("chat");
                            String chatId = String.valueOf(chat.path("id").asLong());
                            String chatTitle = chat.has("title")
                                    ? chat.path("title").asText()
                                    : chat.path("first_name").asText(null);

                            // 업데이트 소거
                            long lastUpdateId = updates.get(updates.size() - 1).path("update_id").asLong();
                            clearUpdatesWithOffset(client, lastUpdateId + 1);

                            status = ChatDetectionStatus.detected(chatId, chatTitle);
                            return;
                        }
                    }
                }

                // 4. 타임아웃
                if (!cancelled) {
                    status = ChatDetectionStatus.timeout();
                }
            } catch (Exception e) {
                sessionLog.debug("[TelegramChatDetector] 감지 실패: {}", e.getMessage());
                if (!cancelled) {
                    status = ChatDetectionStatus.timeout();
                }
            }
        }

        void cancel() {
            cancelled = true;
        }

        ChatDetectionStatus getStatus() {
            return status;
        }

        private void deleteWebhook(HttpClient client) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(TELEGRAM_API + "/bot" + token + "/deleteWebhook"))
                        .GET()
                        .build();
                client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        }

        private void clearUpdates(HttpClient client) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(TELEGRAM_API + "/bot" + token + "/getUpdates?offset=-1"))
                        .GET()
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode updates = MAPPER.readTree(response.body()).path("result");
                if (updates.isArray() && !updates.isEmpty()) {
                    long lastUpdateId = updates.get(updates.size() - 1).path("update_id").asLong();
                    clearUpdatesWithOffset(client, lastUpdateId + 1);
                }
            } catch (Exception ignored) {}
        }

        private void clearUpdatesWithOffset(HttpClient client, long offset) {
            try {
                client.send(HttpRequest.newBuilder()
                        .uri(URI.create(TELEGRAM_API + "/bot" + token
                                + "/getUpdates?offset=" + offset))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        }
    }
}
