package me.taromati.almah.web.setup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.taromati.almah.setup.ConfigGenerator;

import java.nio.file.Path;
import java.util.Map;

public record SetupConfigDto(
        DiscordConfig discord,
        TelegramConfig telegram,
        LlmConfig llm,
        EmbeddingConfig embedding,
        AgentConfig agent,
        NotificationConfig notification,
        SearxngConfig searxng,
        AuthConfig auth
) {

    /** config.yml에서 DTO 변환 (GET 응답용). 비밀 값 제외. */
    public static SetupConfigDto fromConfigFile(Path configPath) {
        ConfigGenerator gen = ConfigGenerator.fromExistingConfig(configPath);
        return fromConfigGenerator(gen);
    }

    /** ConfigGenerator에서 DTO 변환 (GET 응답용). 비밀 값 제외. */
    public static SetupConfigDto fromConfigGenerator(ConfigGenerator gen) {
        return new SetupConfigDto(
                new DiscordConfig(
                        gen.hasDiscord(),
                        null,
                        gen.hasDiscord(),
                        gen.getDiscordServerName()
                ),
                new TelegramConfig(
                        gen.hasTelegram(),
                        null,
                        gen.hasTelegram(),
                        gen.getTelegramBotUsername(),
                        gen.getTelegramChannelMappings()
                ),
                new LlmConfig(
                        gen.getLlmProvider(),
                        null,
                        gen.getLlmApiKey() != null && !gen.getLlmApiKey().isEmpty(),
                        gen.getLlmBaseUrl(),
                        gen.getLlmModel(),
                        gen.getLlmCliPath()
                ),
                new EmbeddingConfig(
                        gen.getEmbeddingProvider(),
                        null,
                        gen.getEmbeddingApiKey() != null && !gen.getEmbeddingApiKey().isEmpty(),
                        gen.getEmbeddingBaseUrl(),
                        gen.getEmbeddingModel(),
                        gen.getEmbeddingDimensions()
                ),
                new AgentConfig(
                        gen.getAgentChannelName(),
                        gen.getAgentDataDir()
                ),
                new NotificationConfig(
                        gen.getNotificationChannel()
                ),
                new SearxngConfig(
                        gen.getSearxngUrl()
                ),
                new AuthConfig(
                        gen.getAuthEnabled()
                )
        );
    }

    // ── Nested Config Records ──

    public record DiscordConfig(
            boolean enabled,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String token,
            @JsonProperty(access = JsonProperty.Access.READ_ONLY)
            Boolean tokenSet,
            String serverName
    ) {}

    public record TelegramConfig(
            boolean enabled,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String token,
            @JsonProperty(access = JsonProperty.Access.READ_ONLY)
            Boolean tokenSet,
            String botUsername,
            Map<String, String> channelMappings
    ) {}

    public record LlmConfig(
            String provider,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String apiKey,
            @JsonProperty(access = JsonProperty.Access.READ_ONLY)
            Boolean apiKeySet,
            String baseUrl,
            String model,
            String cliPath
    ) {}

    public record EmbeddingConfig(
            String provider,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String apiKey,
            @JsonProperty(access = JsonProperty.Access.READ_ONLY)
            Boolean apiKeySet,
            String baseUrl,
            String model,
            Integer dimensions
    ) {}

    public record AgentConfig(
            String channelName,
            String dataDir
    ) {}

    public record NotificationConfig(
            String channel
    ) {}

    public record SearxngConfig(
            String url
    ) {}

    public record AuthConfig(
            boolean enabled
    ) {}
}
