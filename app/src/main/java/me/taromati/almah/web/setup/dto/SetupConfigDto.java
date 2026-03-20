package me.taromati.almah.web.setup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.taromati.almah.setup.FullConfig;

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

    /** FullConfig에서 DTO 변환 (GET 응답용). 비밀 값 제외. */
    public static SetupConfigDto fromFullConfig(FullConfig full) {
        return new SetupConfigDto(
                new DiscordConfig(
                        full.hasDiscord(),
                        null,
                        full.hasDiscord(),
                        full.discordServerName()
                ),
                new TelegramConfig(
                        full.hasTelegram(),
                        null,
                        full.hasTelegram(),
                        full.telegramBotUsername(),
                        full.telegramChannelMappings()
                ),
                new LlmConfig(
                        full.llmProvider(),
                        null,
                        full.llmApiKey() != null && !full.llmApiKey().isEmpty(),
                        full.llmBaseUrl(),
                        full.llmModel(),
                        full.llmCliPath()
                ),
                new EmbeddingConfig(
                        full.embeddingProvider(),
                        null,
                        full.embeddingApiKey() != null && !full.embeddingApiKey().isEmpty(),
                        full.embeddingBaseUrl(),
                        full.embeddingModel(),
                        full.embeddingDimensions()
                ),
                new AgentConfig(
                        full.agentChannelName(),
                        full.agentDataDir()
                ),
                new NotificationConfig(
                        full.notificationChannel()
                ),
                new SearxngConfig(
                        full.searxngUrl()
                ),
                new AuthConfig(
                        full.authEnabled()
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
