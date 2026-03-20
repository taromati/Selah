package me.taromati.almah.setup.steps;

import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ConsoleUi;

import java.util.List;

/**
 * Selah setup 임베딩 방식 선택 (S08).
 * ONNX 내장 vs OpenAI API 중 선택하고 config.yml의 llm.embedding 섹션을 생성한다.
 * ONNX 모델 다운로드는 config.yml 생성 이후 SetupWizard에서 수행.
 */
public class EmbeddingStep {

    /**
     * @return true = 다음 단계, false = 이전 단계
     */
    public static boolean run(ConsoleUi ui, ConfigGenerator config) {
        int choice = ui.choose("임베딩 방식을 선택하세요:", List.of(
                "내장 임베딩 (ONNX, 별도 서버 불필요, 권장)",
                "OpenAI API 사용 (api-key 필요)"
        ));

        if (choice == 0) return false;

        if (choice == 1) {
            // ONNX: 선택만 기록, 다운로드는 config.yml 생성 후 수행
            ui.info("내장 임베딩(ONNX)을 사용합니다.");
            config.onnxSelected(true);
        } else {
            // HTTP: API 키, 모델 설정
            config.embeddingProvider("http");
            String apiKey = ui.promptSecret("OpenAI API Key");
            config.embeddingApiKey(apiKey);
            config.embeddingBaseUrl("https://api.openai.com/v1");

            String model = ui.prompt("모델명", "text-embedding-3-small");
            config.embeddingModel(model);

            String dimensionsStr = ui.prompt("차원", "1536");
            try {
                int dimensions = Integer.parseInt(dimensionsStr);
                config.embeddingDimensions(dimensions);
            } catch (NumberFormatException e) {
                config.embeddingDimensions(1536);
            }
        }
        return true;
    }
}
