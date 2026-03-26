package me.taromati.almah.setup.steps;

import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ConsoleUi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LlmConfigStep {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * @return true = 다음 단계, false = 이전 단계
     */
    public static boolean run(ConsoleUi ui, ConfigGenerator config, String provider) {
        return switch (provider) {
            case "openai" -> configureOpenAi(ui, config, provider);
            case "vllm" -> configureVllm(ui, config, provider);
            case "gemini-cli" -> configureGeminiCli(ui, config, provider);
            case "openai-codex" -> configureCodex(ui, config, provider);
            default -> true;
        };
    }

    private static boolean configureOpenAi(ConsoleUi ui, ConfigGenerator config, String provider) {
        String apiKey = ui.promptSecret("OpenAI API Key");
        if (apiKey.isEmpty()) {
            ui.warn("API Key를 건너뜁니다. config.yml에서 직접 설정해주세요.");
            config.llmApiKey("YOUR_OPENAI_API_KEY");
        } else {
            config.llmApiKey(apiKey);
        }

        // 모델 목록 API 조회
        String model = selectModelFromApi(ui, "https://api.openai.com/v1/models", apiKey, "gpt-5.4");
        if (model == null) return false;
        config.llmModel(model);

        // context-window 입력
        ui.info("https://platform.openai.com/docs/models 에서 모델별 context window를 확인하세요");
        int contextWindow = promptContextWindow(ui, provider);
        if (contextWindow > 0) config.contextWindow(contextWindow);
        return true;
    }

    private static boolean configureVllm(ConsoleUi ui, ConfigGenerator config, String provider) {
        String baseUrl = ui.prompt("vLLM 서버 URL", "http://localhost:8000/v1");
        config.llmBaseUrl(baseUrl);

        // 모델 목록 API 조회
        String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
        String model = selectModelFromApi(ui, modelsUrl, null, null);
        if (model == null) return false;
        config.llmModel(model);

        // context-window 입력
        int contextWindow = promptContextWindow(ui, provider);
        if (contextWindow > 0) config.contextWindow(contextWindow);
        return true;
    }

    private static boolean configureGeminiCli(ConsoleUi ui, ConfigGenerator config, String provider) {
        ui.info("Gemini CLI는 별도 인증이 필요합니다. (gemini auth login)");
        String cliPath = ui.prompt("Gemini CLI 경로", "gemini");
        config.llmCliPath(cliPath);

        String model = ui.prompt("모델", "gemini-2.5-pro");
        config.llmModel(model);

        // context-window 입력
        ui.info("https://ai.google.dev/gemini-api/docs/models 에서 모델별 context window를 확인하세요");
        int contextWindow = promptContextWindow(ui, provider);
        if (contextWindow > 0) config.contextWindow(contextWindow);
        return true;
    }

    private static boolean configureCodex(ConsoleUi ui, ConfigGenerator config, String provider) {
        ui.info("OpenAI Codex는 ChatGPT 계정 인증이 필요합니다.");
        ui.info("터미널에서 'codex login'을 실행하여 ~/.codex/auth.json을 생성하세요.");
        ui.info("(codex CLI: npm install -g @openai/codex)");

        // auth.json 존재 확인
        var authFile = java.nio.file.Path.of(System.getProperty("user.home"), ".codex", "auth.json");
        if (java.nio.file.Files.exists(authFile)) {
            ui.success("~/.codex/auth.json 발견 — 토큰이 준비되어 있습니다.");
        } else {
            ui.warn("~/.codex/auth.json이 없습니다.");
            int action = ui.choose("어떻게 하시겠습니까?", List.of(
                    "나중에 설정 (config.yml에서 직접 수정)",
                    "다른 프로바이더 선택"
            ));
            if (action == 0 || action == 2) return false;
            // action == 1: 나중에 설정 — 기본값으로 진행
        }

        String model = ui.prompt("모델", "gpt-5.4");
        config.llmModel(model);

        int contextWindow = promptContextWindow(ui, provider);
        if (contextWindow > 0) config.contextWindow(contextWindow);
        return true;
    }

    // ── 공통 유틸 ──

    /**
     * /v1/models API를 호출해 모델 목록을 가져오고 선택하게 합니다.
     * 실패 시 직접 입력으로 폴백합니다.
     * @return 선택된 모델명. null = 이전 단계.
     */
    private static String selectModelFromApi(ConsoleUi ui, String modelsUrl, String apiKey, String defaultModel) {
        List<String> models = fetchModelList(modelsUrl, apiKey);

        if (models.isEmpty()) {
            ui.warn("모델 목록을 가져올 수 없습니다. 직접 입력해주세요.");
            return defaultModel != null ? ui.prompt("모델", defaultModel) : ui.prompt("모델명");
        }

        ui.info("사용 가능한 모델 (" + models.size() + "개):");
        // 최대 20개까지 번호로 표시
        int displayCount = Math.min(models.size(), 20);
        List<String> displayModels = new ArrayList<>(models.subList(0, displayCount));
        if (models.size() > 20) {
            displayModels.add("직접 입력...");
        }

        int choice = ui.choose("모델을 선택하세요:", displayModels);
        if (choice == 0) return null;
        if (choice <= displayCount) {
            return displayModels.get(choice - 1);
        } else {
            // "직접 입력..." 선택
            return defaultModel != null ? ui.prompt("모델명", defaultModel) : ui.prompt("모델명");
        }
    }

    private static List<String> fetchModelList(String modelsUrl, String apiKey) {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            if (apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("YOUR_")) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = HTTP_CLIENT.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();

            return parseModelIds(response.body());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * JSON 응답에서 모델 ID를 추출합니다 (외부 JSON 라이브러리 없이 정규식 사용).
     * OpenAI/vLLM 공통 형식: {"data": [{"id": "model-name", ...}, ...]}
     */
    static List<String> parseModelIds(String json) {
        List<String> models = new ArrayList<>();
        Pattern pattern = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            models.add(matcher.group(1));
        }
        models.sort(String::compareTo);
        return models;
    }

    private static int promptContextWindow(ConsoleUi ui, String provider) {
        String defaultValue = switch (provider) {
            case "openai", "openai-codex" -> "272000";
            case "gemini-cli" -> "1048576";
            default -> "128000";  // vLLM 등
        };
        String input = ui.prompt("Context Window (토큰 수)", defaultValue);
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return Integer.parseInt(defaultValue);
        }
    }
}
