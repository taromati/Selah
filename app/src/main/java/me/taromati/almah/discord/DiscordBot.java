package me.taromati.almah.discord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.discord.DiscordMessengerGateway;
import me.taromati.almah.discord.config.DiscordConfigProperties;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

@Slf4j
@RequiredArgsConstructor
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "discord.enabled", havingValue = "true", matchIfMissing = false)
public class DiscordBot {
    private final DiscordConfigProperties discordConfigProperties;
    private final ApplicationContext applicationContext;
    private final DiscordMessengerGateway discordMessengerGateway;
    private JDA jda;

    @PreDestroy
    public void shutdown() {
        if (jda != null) {
            log.info("[DiscordBot] Shutting down JDA...");
            jda.shutdownNow();
        }
    }

    @EventListener(ApplicationStartedEvent.class)
    public void onApplicationStartedEvent(ApplicationStartedEvent event) {
        String token = discordConfigProperties.getToken();
        if (token == null || token.isEmpty() || token.startsWith("YOUR_")) {
            log.warn("[DiscordBot] Discord 토큰이 설정되지 않았습니다. 웹 UI만 동작합니다.");
            return;
        }

        try {
            JDABuilder builder = JDABuilder.createLight(token, EnumSet.of(
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.MESSAGE_CONTENT,
                    GatewayIntent.GUILD_MESSAGE_REACTIONS,
                    GatewayIntent.GUILD_MEMBERS
            )).setMemberCachePolicy(MemberCachePolicy.DEFAULT);

            var listeners = applicationContext.getBeansOfType(ListenerAdapter.class).values();
            listeners.forEach(listener -> {
                log.debug("[DiscordBot] Registering listener: {}", listener.getClass().getSimpleName());
                builder.addEventListeners(listener);
            });

            this.jda = builder.build().awaitReady();
            discordMessengerGateway.initialize(jda, discordConfigProperties.getServerName());

            log.info("[DiscordBot] Discord 연결 완료. 리스너 {}개 등록", listeners.size());
        } catch (Exception e) {
            log.error("[DiscordBot] Discord 연결 실패 — 웹 UI만 동작합니다: {}", e.getMessage());
        }
    }
}
