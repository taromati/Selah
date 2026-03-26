package me.taromati.almah.setup.steps;

import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ConsoleUi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class DiscordTokenStep {

    private static final String DEFAULT_BASE_URL = "https://discord.com";

    public static void run(ConsoleUi ui, ConfigGenerator config) {
        run(ui, config, DEFAULT_BASE_URL);
    }

    static void run(ConsoleUi ui, ConfigGenerator config, String baseUrl) {
        ui.info("Discord 봇 토큰을 입력해주세요.");
        ui.info("아직 없다면 https://discord.com/developers/applications 에서 생성할 수 있습니다.");

        while (true) {
            String token = ui.promptSecret("봇 토큰");
            if (token.isEmpty()) {
                ui.warn("토큰을 건너뜁니다. 나중에 config.yml에서 직접 설정할 수 있습니다.");
                config.discordToken("YOUR_DISCORD_BOT_TOKEN");
                break;
            }

            ui.info("토큰 검증 중...");
            if (validateToken(token, baseUrl)) {
                ui.success("토큰 검증 완료!");
                config.discordToken(token);

                String serverName = ui.prompt("Discord 서버 이름");
                config.serverName(serverName);
                break;
            } else {
                ui.error("토큰이 유효하지 않습니다. 다시 입력해주세요.");
            }
        }
    }

    static boolean validateToken(String token, String baseUrl) {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v10/users/@me"))
                    .header("Authorization", "Bot " + token)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
