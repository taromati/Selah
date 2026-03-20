package me.taromati.almah.web.setup;

import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.FullConfig;
import me.taromati.almah.setup.ServiceInstaller;
import me.taromati.almah.web.setup.dto.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * config.yml의 읽기/쓰기를 담당.
 * FullConfig + ConfigGenerator를 활용한다.
 */
@Service
public class SetupConfigService {

    private static final Path CONFIG_PATH = Path.of("config.yml");

    /**
     * 현재 config.yml을 읽어 DTO로 변환.
     * 비밀 값(토큰, API Key)은 포함하지 않고 "설정 여부"만 반환.
     */
    public SetupConfigDto readConfig() {
        FullConfig full = FullConfig.parse(CONFIG_PATH);
        return SetupConfigDto.fromFullConfig(full);
    }

    /**
     * DTO의 값을 ConfigGenerator에 반영하여 config.yml 재생성.
     *
     * 핵심 원칙: read-modify-write.
     * - 기존 config.yml의 모든 값을 ConfigGenerator.fromFullConfig()로 복사
     * - DTO에서 변경된 필드만 덮어쓰기
     * - DTO에서 null인 비밀 필드 -> 기존 값 유지
     * - DTO에서 값이 있는 비밀 필드 -> 새 값으로 교체
     * - DTO에서 명시적 빈 문자열 -> 해당 설정 제거
     */
    public ConfigSaveResult saveConfig(SetupConfigDto dto) throws IOException {
        FullConfig existing = FullConfig.parse(CONFIG_PATH);

        // 최소 1개 메신저 활성 검증
        boolean discordOn = (dto.discord() != null && dto.discord().enabled())
                || (dto.discord() == null && existing.hasDiscord());
        boolean telegramOn = (dto.telegram() != null && dto.telegram().enabled())
                || (dto.telegram() == null && existing.hasTelegram());
        if (!discordOn && !telegramOn) {
            throw new IllegalArgumentException("최소 1개의 메신저가 필요합니다");
        }

        ConfigGenerator generator = buildGenerator(dto, existing);

        // Atomic write: tmpFile에 쓰고 move
        Path tmpFile = CONFIG_PATH.resolveSibling("config.yml.tmp");
        generator.generate(tmpFile);
        Files.move(tmpFile, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        boolean serviceRegistered = isServiceRegistered();
        return new ConfigSaveResult(true, serviceRegistered);
    }

    /**
     * 서비스 등록 여부 확인 후 재시작 트리거.
     */
    public RestartResult restart() {
        var result = me.taromati.almah.setup.ServiceRestarter.restart(true);
        return result.success() ? RestartResult.ok() : RestartResult.noService();
    }

    boolean isServiceRegistered() {
        String os = ServiceInstaller.detectOs();
        try {
            return switch (os) {
                case "macos" -> {
                    var proc = new ProcessBuilder("launchctl", "list", "me.taromati.selah")
                            .redirectErrorStream(true).start();
                    yield proc.waitFor() == 0;
                }
                case "linux" -> {
                    var proc = new ProcessBuilder("systemctl", "--user", "is-enabled", "selah")
                            .redirectErrorStream(true).start();
                    yield proc.waitFor() == 0;
                }
                case "windows" -> {
                    var proc = new ProcessBuilder("schtasks", "/query", "/tn", "Selah")
                            .redirectErrorStream(true).start();
                    yield proc.waitFor() == 0;
                }
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 기존 config의 모든 값을 복사한 뒤, DTO에서 변경된 필드만 덮어쓴다.
     * fromFullConfig()가 모든 값을 복사하므로 구조적으로 누락이 불가능하다.
     */
    ConfigGenerator buildGenerator(SetupConfigDto dto, FullConfig existing) {
        ConfigGenerator gen = ConfigGenerator.fromFullConfig(existing);

        // Discord
        if (dto.discord() != null) {
            gen.discordEnabled(dto.discord().enabled());
            String token = resolveSecret(dto.discord().token(), existing.discordToken());
            gen.discordToken(token != null ? token : "");
            gen.serverName(firstNonNull(dto.discord().serverName(), existing.discordServerName(), ""));
        }

        // Telegram
        if (dto.telegram() != null) {
            gen.telegramEnabled(dto.telegram().enabled());
            String token = resolveSecret(dto.telegram().token(), existing.telegramToken());
            gen.telegramToken(token != null ? token : "");
            gen.telegramBotUsername(firstNonNull(
                    dto.telegram().botUsername(), existing.telegramBotUsername(), ""));
            if (dto.telegram().channelMappings() != null) {
                gen.clearTelegramChannelMappings();
                dto.telegram().channelMappings().forEach(gen::telegramChannelMapping);
            }
        }

        // LLM
        if (dto.llm() != null) {
            if (dto.llm().provider() != null) {
                gen.llmProvider(dto.llm().provider());
            }
            String llmKey = resolveSecret(dto.llm().apiKey(), existing.llmApiKey());
            gen.llmApiKey(llmKey != null ? llmKey : "");
            gen.llmBaseUrl(firstNonNull(dto.llm().baseUrl(), existing.llmBaseUrl(), ""));
            gen.llmModel(firstNonNull(dto.llm().model(), existing.llmModel(), ""));
            gen.llmCliPath(firstNonNull(dto.llm().cliPath(), existing.llmCliPath(), ""));
        }

        // Embedding
        if (dto.embedding() != null) {
            gen.embeddingProvider(firstNonNull(dto.embedding().provider(), existing.embeddingProvider(), ""));
            if ("http".equals(dto.embedding().provider())) {
                gen.embeddingBaseUrl(firstNonNull(dto.embedding().baseUrl(), existing.embeddingBaseUrl(), ""));
                String embKey = resolveSecret(dto.embedding().apiKey(), existing.embeddingApiKey());
                gen.embeddingApiKey(embKey != null ? embKey : "");
                gen.embeddingModel(firstNonNull(dto.embedding().model(), existing.embeddingModel(), ""));
                gen.embeddingDimensions(
                        dto.embedding().dimensions() > 0
                                ? dto.embedding().dimensions()
                                : existing.embeddingDimensions());
            }
        }

        // Agent
        if (dto.agent() != null) {
            if (dto.agent().channelName() != null) gen.agentChannelName(dto.agent().channelName());
            if (dto.agent().dataDir() != null) gen.agentDataDir(dto.agent().dataDir());
        }

        // Notification
        if (dto.notification() != null && dto.notification().channel() != null) {
            gen.notificationChannel(dto.notification().channel());
        }

        // SearXNG
        if (dto.searxng() != null) {
            gen.searxngUrl(dto.searxng().url() != null ? dto.searxng().url() : "");
        }

        // Web Auth
        if (dto.auth() != null) {
            gen.authEnabled(dto.auth().enabled());
        }

        return gen;
    }

    /**
     * 비밀 값 해석:
     * - null: 기존 값 유지
     * - "": 제거 (null 반환)
     * - 새 값: 새 값 사용
     */
    private static String resolveSecret(String dtoValue, String existingValue) {
        if (dtoValue == null) return existingValue;      // 기존 값 유지
        if (dtoValue.isEmpty()) return null;              // 제거
        return dtoValue;                                  // 새 값
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return "";
    }
}
