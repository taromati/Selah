package me.taromati.almah.web.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.embedding.EmbeddingProperties;
import me.taromati.almah.web.setup.dto.*;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Slf4j
@Service
public class SetupValidationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EmbeddingProperties embeddingProperties;

    public SetupValidationService(EmbeddingProperties embeddingProperties) {
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * Discord 토큰 검증. Discord API(/api/v10/users/@me) 호출.
     * @return 검증 결과 + 봇 이름(username)
     */
    public DiscordValidation validateDiscordToken(String token) {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/users/@me"))
                    .header("Authorization", "Bot " + token)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(response.body());
                String username = json.path("username").asText(null);
                return new DiscordValidation(true, username);
            }
        } catch (Exception e) {
            log.debug("[SetupValidation] Discord 토큰 검증 실패: {}", e.getMessage());
        }
        return DiscordValidation.invalid();
    }

    /**
     * Telegram 토큰 검증. getMe API 호출.
     * @return 봇 이름, username 또는 null(실패)
     */
    public TelegramValidation validateTelegramToken(String token) {
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
                String firstName = result.path("first_name").asText(null);
                if (username != null) {
                    return new TelegramValidation(true, username, firstName);
                }
            }
        } catch (Exception e) {
            log.debug("[SetupValidation] Telegram 토큰 검증 실패: {}", e.getMessage());
        }
        return TelegramValidation.invalid();
    }

    /**
     * LLM 연결 테스트. provider별 엔드포인트에 요청.
     */
    public ConnectionTest testLlmConnection(String provider, String apiKey, String baseUrl, String cliPath) {
        long start = System.currentTimeMillis();
        boolean success = switch (provider) {
            case "openai" -> testHttpGet("https://api.openai.com/v1/models", "Bearer " + apiKey);
            case "vllm" -> testHttpGet(normalizeVllmUrl(baseUrl) + "/models", null);
            case "gemini-cli" -> testCliExists(cliPath != null ? cliPath : "gemini");
            case "openai-codex" -> java.nio.file.Files.exists(
                    java.nio.file.Path.of(System.getProperty("user.home"), ".codex", "auth.json"));
            default -> false;
        };
        long responseTimeMs = System.currentTimeMillis() - start;
        return new ConnectionTest(success, responseTimeMs);
    }

    /**
     * 임베딩 HTTP 연결 테스트. 테스트 벡터 요청.
     */
    public ConnectionTest testEmbeddingConnection(String apiKey, String baseUrl, String model) {
        long start = System.currentTimeMillis();
        boolean success = testEmbeddingEndpoint(baseUrl, apiKey, model);
        long responseTimeMs = System.currentTimeMillis() - start;
        return new ConnectionTest(success, responseTimeMs);
    }

    /**
     * ONNX 모델 상태 확인.
     */
    public OnnxStatus getOnnxStatus() {
        var onnxConfig = embeddingProperties.getOnnx();
        Path cacheDir = resolveOnnxCacheDir(onnxConfig);
        String dirName = onnxConfig.getModelRepo().replace('/', '_').replace('\\', '_');
        Path modelDir = cacheDir.resolve(dirName);
        Path modelFile = modelDir.resolve(onnxConfig.getModelFile());
        Path tokenizerFile = modelDir.resolve(onnxConfig.getTokenizerFile());

        boolean downloaded = Files.exists(modelFile) && Files.exists(tokenizerFile);
        String modelSize = "";
        if (downloaded) {
            try {
                long bytes = Files.size(modelFile);
                modelSize = (bytes / (1024 * 1024)) + "MB";
            } catch (Exception e) {
                modelSize = "unknown";
            }
        }
        return new OnnxStatus(downloaded, modelSize, modelDir.toString());
    }

    private Path resolveOnnxCacheDir(EmbeddingProperties.OnnxConfig config) {
        if (config.getModelCacheDir() != null && !config.getModelCacheDir().isBlank()) {
            return Path.of(config.getModelCacheDir());
        }
        return Path.of(System.getProperty("user.home"), ".selah", "models");
    }

    /**
     * SearXNG 연결 테스트.
     */
    public ConnectionTest testSearxng(String url) {
        long start = System.currentTimeMillis();
        boolean success = testHttpGet(url + "/search?q=test&format=json", null);
        long responseTimeMs = System.currentTimeMillis() - start;
        return new ConnectionTest(success, responseTimeMs);
    }

    // ── 내부 유틸 ──

    boolean testHttpGet(String url, String authorization) {
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

    private boolean testCliExists(String cliPath) {
        try {
            // 절대 경로인 경우 파일 존재 확인
            Path path = Path.of(cliPath);
            if (path.isAbsolute()) {
                return Files.isExecutable(path);
            }
            // 상대 경로 / 명령어인 경우 which로 확인
            var process = new ProcessBuilder("which", cliPath)
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean testEmbeddingEndpoint(String baseUrl, String apiKey, String model) {
        try (var client = HttpClient.newHttpClient()) {
            String body = MAPPER.writeValueAsString(new EmbeddingRequest(model != null ? model : "text-embedding-3-small", "test"));
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10));
            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeVllmUrl(String baseUrl) {
        if (baseUrl == null) return "http://localhost:8000/v1";
        // /v1으로 끝나면 그대로, 아니면 /v1 추가
        if (baseUrl.endsWith("/v1")) return baseUrl;
        if (baseUrl.endsWith("/")) return baseUrl + "v1";
        return baseUrl + "/v1";
    }

    private record EmbeddingRequest(String model, String input) {}
}
