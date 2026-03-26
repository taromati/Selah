package me.taromati.almah.setup.steps;

import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ConsoleUi;

import java.util.List;

public class LlmProviderStep {

    /**
     * @return 선택된 provider 이름. null = 이전 단계.
     */
    public static String run(ConsoleUi ui, ConfigGenerator config) {
        int choice = ui.choose("LLM 프로바이더를 선택하세요:", List.of(
                "OpenAI Codex (ChatGPT — codex login 필요)",
                "OpenAI (GPT-4o, GPT-4o-mini 등)",
                "vLLM (자체 호스팅 모델)",
                "Gemini CLI (Google Gemini)"
        ));

        if (choice == 0) return null;

        String provider = switch (choice) {
            case 1 -> "openai-codex";
            case 2 -> "openai";
            case 3 -> "vllm";
            case 4 -> "gemini-cli";
            default -> "openai-codex";
        };

        config.llmProvider(provider);
        return provider;
    }
}
