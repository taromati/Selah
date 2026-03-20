package me.taromati.almah.setup.steps;

import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ConsoleUi;

import java.nio.file.*;

public class AgentStep {

    /**
     * @return true = 다음 단계, false = 이전 단계
     */
    public static boolean run(ConsoleUi ui, ConfigGenerator config) {
        // Discord 사용 시만 채널 이름 질문 (Telegram은 channel-mappings로 라우팅)
        if (config.isDiscordEnabled()) {
            String channelName = ui.prompt("Agent 채널 이름", "agent");
            config.agentChannelName(channelName);
        }

        String dataDir = ui.prompt("Agent 데이터 디렉토리", "./agent-data/");
        config.agentDataDir(dataDir);

        // 기본 에이전트 파일 복사 여부 (실제 복사는 config.yml 생성 후 SetupWizard에서 수행)
        Path targetDir = Path.of(dataDir);
        if (!Files.exists(targetDir) || isDirectoryEmpty(targetDir)) {
            config.copyAgentDefaults(true);
        } else {
            ui.info("에이전트 데이터 디렉토리가 이미 존재합니다.");
        }

        // SearXNG (웹 검색)
        boolean searxngInstalled = Files.exists(Path.of("searxng/.venv/bin/searxng-run"))
                || Files.exists(Path.of("searxng/.venv/Scripts/searxng-run.exe"));
        if (searxngInstalled) {
            ui.success("SearXNG 설치 확인됨");
            String searxngUrl = ui.prompt("SearXNG URL", "http://localhost:8888");
            config.searxngUrl(searxngUrl);
        } else {
            ui.warn("SearXNG가 설치되지 않았습니다. 웹 검색이 비활성화됩니다.");
            ui.info("재설치: curl -fsSL https://raw.githubusercontent.com/taromati/Selah/main/install-searxng.sh | bash");
        }
        return true;
    }

    private static boolean isDirectoryEmpty(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findFirst().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }
}
