package me.taromati.almah.setup;

import me.taromati.almah.llm.embedding.EmbeddingProperties;
import me.taromati.almah.llm.embedding.onnx.ModelDownloader;
import me.taromati.almah.setup.steps.AgentStep;
import me.taromati.almah.setup.steps.EmbeddingStep;
import me.taromati.almah.setup.steps.LlmConfigStep;
import me.taromati.almah.setup.steps.LlmProviderStep;
import me.taromati.almah.setup.steps.MessengerStep;

import java.io.IOException;
import java.nio.file.*;

/**
 * CLI 온보딩 위저드. Spring Context 없이 순수 Java로 동작.
 * 실행 흐름: 메신저 선택 -> LLM 선택 -> LLM 설정 -> 임베딩 설정 -> Agent 설정 -> config.yml 생성
 * 각 단계에서 0 입력으로 이전 단계 복귀, Step 0에서 0 입력 시 전체 취소.
 */
public class SetupWizard {

    private static final String[] STEP_TITLES = {
            "1/5  메신저 설정",
            "2/5  LLM 프로바이더 선택",
            "3/5  LLM 설정",
            "4/5  임베딩 설정",
            "5/5  Agent 설정"
    };

    public static void run() {
        ConsoleUi ui = new ConsoleUi();
        ConfigGenerator config = new ConfigGenerator();

        ui.banner();

        Path configPath = Path.of("config.yml");
        ExistingConfig existing = null;
        if (Files.exists(configPath)) {
            ui.warn("config.yml이 이미 존재합니다.");
            if (!ui.confirm("덮어쓰시겠습니까?")) {
                ui.info("설정을 취소합니다.");
                return;
            }
            existing = ExistingConfig.parse(configPath);
        }

        int step = 0;
        String provider = null;

        while (step < STEP_TITLES.length) {
            ui.section(STEP_TITLES[step]);

            boolean next = switch (step) {
                case 0 -> MessengerStep.run(ui, config, existing);
                case 1 -> {
                    provider = LlmProviderStep.run(ui, config);
                    yield provider != null;
                }
                case 2 -> LlmConfigStep.run(ui, config, provider);
                case 3 -> EmbeddingStep.run(ui, config);
                case 4 -> AgentStep.run(ui, config);
                default -> true;
            };

            if (next) {
                step++;
            } else if (step == 0) {
                ui.info("설정을 취소합니다.");
                return;
            } else {
                resetConfigForStep(config, step);
                step--;
            }
        }

        // config.yml 생성
        try {
            config.generate(configPath);
            ui.success("config.yml 생성 완료!");
            ui.info("");

            // 후처리: ONNX 모델 다운로드
            if (config.isOnnxSelected()) {
                ui.info("임베딩 모델 다운로드 중... (약 127MB)");
                try {
                    EmbeddingProperties.OnnxConfig onnxConfig = new EmbeddingProperties.OnnxConfig();
                    ModelDownloader downloader = new ModelDownloader(onnxConfig);
                    downloader.ensureModel();
                    ui.success("임베딩 모델 다운로드 완료.");
                } catch (Exception e) {
                    ui.warn("모델 다운로드 실패. 앱은 시작 가능하나 임베딩 검색이 비활성화됩니다.");
                    ui.info("원인: " + e.getMessage());
                }
            }

            // 후처리: agent-data 기본 파일 생성
            if (config.isCopyAgentDefaults()) {
                Path targetDir = Path.of(config.getAgentDataDir());
                try {
                    extractDefaultFiles(targetDir);
                    ui.success("에이전트 파일 복사 완료");
                } catch (IOException e) {
                    ui.error("파일 복사 실패: " + e.getMessage());
                }
            }

            // 서비스 등록 + 즉시 시작
            ui.info("서비스를 등록합니다...");
            try {
                ServiceInstaller.install();
            } catch (Exception e) {
                ui.warn("서비스 등록 실패: " + e.getMessage());
                ui.info("나중에 'selah enable'로 직접 등록할 수 있습니다.");
            }

            ui.info("");
            ui.info("유용한 명령어:");
            ui.info("  설정 검증:    selah doctor");
            ui.info("  서비스 해제:  selah disable");
            String uninstallCmd = "windows".equals(ServiceInstaller.detectOs())
                    ? "powershell -File \"%USERPROFILE%\\.selah\\uninstall.ps1\""
                    : "~/.selah/uninstall.sh";
            ui.info("  제거:         " + uninstallCmd);
        } catch (Exception e) {
            ui.error("config.yml 생성 실패: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void resetConfigForStep(ConfigGenerator config, int step) {
        switch (step) {
            case 0 -> config.resetMessenger();
            case 1 -> config.resetLlmProvider();
            case 2 -> config.resetLlmConfig();
            case 3 -> config.resetEmbedding();
            case 4 -> config.resetAgent();
        }
    }

    private static final String[] DEFAULT_FILES = {
            "PERSONA.md", "GUIDE.md", "TOOLS.md", "MEMORY.md"
    };

    private static void extractDefaultFiles(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        for (String filename : DEFAULT_FILES) {
            Path targetFile = targetDir.resolve(filename);
            if (Files.exists(targetFile)) continue;
            // Spring Boot fat JAR에서는 ClassLoader를 통해 접근해야 BOOT-INF/classes/ 내 리소스를 찾을 수 있음
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = SetupWizard.class.getClassLoader();
            var is = cl.getResourceAsStream("defaults/agent-data/" + filename);
            if (is != null) {
                try (is) {
                    Files.copy(is, targetFile);
                }
            }
        }
    }
}
