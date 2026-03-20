package me.taromati.almah.setup;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기존 config.yml을 읽어서 메신저 설정 상태를 파싱한다.
 * S05 (setup 재실행) 시나리오에서 사용.
 */
public class ExistingConfig {

    private final boolean discordEnabled;
    private final String discordToken;
    private final String discordServerName;
    private final boolean telegramEnabled;
    private final String telegramToken;
    private final String telegramBotUsername;
    private final Map<String, String> telegramChannelMappings;

    ExistingConfig(boolean discordEnabled, String discordToken, String discordServerName,
                   boolean telegramEnabled, String telegramToken,
                   String telegramBotUsername, Map<String, String> telegramChannelMappings) {
        this.discordEnabled = discordEnabled;
        this.discordToken = discordToken;
        this.discordServerName = discordServerName;
        this.telegramEnabled = telegramEnabled;
        this.telegramToken = telegramToken;
        this.telegramBotUsername = telegramBotUsername;
        this.telegramChannelMappings = telegramChannelMappings;
    }

    public static ExistingConfig parse(Path configPath) {
        String content;
        try {
            content = Files.readString(configPath);
        } catch (IOException e) {
            return empty();
        }
        return parseContent(content);
    }

    static ExistingConfig parseContent(String content) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(content);
        if (root == null) root = Map.of();
        return fromMap(root);
    }

    @SuppressWarnings("unchecked")
    static ExistingConfig fromMap(Map<String, Object> root) {
        // Discord
        boolean discordEnabled = YamlHelper.getBool(root, false, "discord", "enabled");
        String discordToken = YamlHelper.getString(root, "discord", "token");
        String discordServerName = YamlHelper.getString(root, "discord", "server-name");
        // discord.enabled가 없지만 토큰이 있으면 활성으로 간주 (하위호환)
        if (!discordEnabled && discordToken != null && !discordToken.isEmpty()) {
            discordEnabled = true;
        }

        // Telegram
        boolean telegramEnabled = YamlHelper.getBool(root, false, "telegram", "enabled");
        String telegramToken = YamlHelper.getString(root, "telegram", "token");
        String telegramBotUsername = YamlHelper.getString(root, "telegram", "bot-username");

        // channel-mappings: 동적으로 모든 키 읽기
        Map<String, String> mappings = new LinkedHashMap<>();
        Object rawMappings = YamlHelper.getPath(root, "telegram", "channel-mappings");
        if (rawMappings instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    mappings.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
        }

        return new ExistingConfig(discordEnabled, discordToken, discordServerName,
                telegramEnabled, telegramToken, telegramBotUsername, mappings);
    }

    public boolean hasAnyMessenger() {
        return hasDiscord() || hasTelegram();
    }

    public boolean hasDiscord() {
        return discordToken != null && !discordToken.isEmpty()
                && !discordToken.startsWith("YOUR_");
    }

    public boolean hasTelegram() {
        return telegramEnabled && telegramToken != null
                && !telegramToken.isEmpty() && !telegramToken.startsWith("YOUR_");
    }

    // ── Getters (FullConfig + setup steps에서 사용) ──

    public boolean discordEnabled() { return discordEnabled; }
    public String discordToken() { return discordToken; }
    public String discordServerName() { return discordServerName; }
    public boolean telegramEnabled() { return telegramEnabled; }
    public String telegramToken() { return telegramToken; }
    public String telegramBotUsername() { return telegramBotUsername; }
    public Map<String, String> telegramChannelMappings() { return telegramChannelMappings; }

    public static ExistingConfig empty() {
        return new ExistingConfig(false, null, null, false, null, null, Map.of());
    }

    public void copyMessengerTo(ConfigGenerator config) {
        if (hasDiscord()) {
            config.discordEnabled(discordEnabled);
            config.discordToken(discordToken);
            config.serverName(discordServerName != null ? discordServerName : "");
        }
        if (hasTelegram()) {
            config.telegramEnabled(true);
            config.telegramToken(telegramToken);
            config.telegramBotUsername(telegramBotUsername != null ? telegramBotUsername : "");
            telegramChannelMappings.forEach(config::telegramChannelMapping);
        }
    }
}
