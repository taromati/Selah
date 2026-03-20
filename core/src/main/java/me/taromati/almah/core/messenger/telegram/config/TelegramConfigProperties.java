package me.taromati.almah.core.messenger.telegram.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "telegram")
public class TelegramConfigProperties {
    private boolean enabled;
    private String token;
    private String botUsername;
    private Map<String, String> channelMappings = Map.of();
}
