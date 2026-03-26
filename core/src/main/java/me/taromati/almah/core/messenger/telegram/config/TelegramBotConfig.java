package me.taromati.almah.core.messenger.telegram.config;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.telegram.TelegramInteractionRouter;
import me.taromati.almah.core.messenger.telegram.TelegramPluginRouter;
import me.taromati.almah.core.messenger.ActionEvent;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerPlatform;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramConfigProperties.class)
public class TelegramBotConfig {

    @Bean
    public TelegramBot telegramBot(TelegramConfigProperties config) {
        return new TelegramBot(config.getToken());
    }

    @EventListener(ApplicationStartedEvent.class)
    public void startLongPolling(ApplicationStartedEvent event) {
        var ctx = event.getApplicationContext();
        TelegramBot bot = ctx.getBean(TelegramBot.class);

        // 순환 의존 방지: field injection 대신 ApplicationContext에서 직접 조회
        TelegramPluginRouter pluginRouter = ctx.getBeanProvider(TelegramPluginRouter.class).getIfAvailable();
        TelegramInteractionRouter interactionRouter = ctx.getBeanProvider(TelegramInteractionRouter.class).getIfAvailable();
        log.info("[TelegramBot] interactionRouter={}, pluginRouter={}", interactionRouter != null, pluginRouter != null);

        var getUpdates = new com.pengrad.telegrambot.request.GetUpdates()
                .allowedUpdates("message", "callback_query");

        bot.setUpdatesListener(updates -> {
            for (var update : updates) {
                try {
                    if (update.callbackQuery() != null) {
                        var cq = update.callbackQuery();
                        log.info("[TelegramBot] Callback received: data={}, interactionRouter={}", cq.data(), interactionRouter != null);
                        if (interactionRouter != null) {
                            String messageId = String.valueOf(cq.message().messageId());
                            String chatId = String.valueOf(cq.message().chat().id());
                            ChannelRef channel = ChannelRef.of(MessengerPlatform.TELEGRAM, chatId);
                            interactionRouter.processCallbackQuery(
                                    messageId,
                                    new ActionEvent(cq.data(), String.valueOf(cq.from().id()), messageId, channel),
                                    chatId, cq.id());
                        }
                    } else if (pluginRouter != null) {
                        pluginRouter.processUpdate(update);
                    }
                } catch (Exception e) {
                    log.error("[TelegramBot] Update 처리 실패", e);
                }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        }, getUpdates);

        log.info("[TelegramBot] Long-polling 시작");
    }
}
