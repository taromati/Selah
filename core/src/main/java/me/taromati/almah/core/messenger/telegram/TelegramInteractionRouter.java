package me.taromati.almah.core.messenger.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.EditMessageReplyMarkup;
import com.pengrad.telegrambot.request.EditMessageText;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramInteractionRouter {

    private final List<InteractionHandler> handlers;
    private final TelegramBot bot;
    private final ConcurrentHashMap<String, TelegramInteractiveMessage> temporaryHandles = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramInteractionRouter(List<InteractionHandler> handlers,
                                      @org.springframework.lang.Nullable TelegramBot bot) {
        this.handlers = handlers;
        this.bot = bot;
    }

    public void registerTemporaryHandle(String messageId, TelegramInteractiveMessage handle) {
        temporaryHandles.put(messageId, handle);
    }

    public void removeTemporaryHandle(String messageId) {
        temporaryHandles.remove(messageId);
    }

    public void processCallbackQuery(String messageId, ActionEvent actionEvent) {
        processCallbackQuery(messageId, actionEvent, null, null);
    }

    public void processCallbackQuery(String messageId, ActionEvent actionEvent,
                                      String chatId, String callbackQueryId) {
        log.info("[TelegramInteractionRouter] Callback: actionId={}, handlers={}", actionEvent.actionId(), handlers.size());

        // 1. 임시 핸들 매칭
        var handle = temporaryHandles.remove(messageId);
        if (handle != null) {
            log.info("[TelegramInteractionRouter] Matched temporary handle for messageId={}", messageId);
            handle.getFuture().complete(actionEvent);
            return;
        }

        // 2. 영구 핸들러 매칭
        InteractionResponder responder = new TelegramInteractionResponder(bot, chatId, messageId, callbackQueryId);
        for (InteractionHandler handler : handlers) {
            if (actionEvent.actionId().startsWith(handler.getActionIdPrefix())) {
                log.info("[TelegramInteractionRouter] Matched handler: {} for actionId={}", handler.getClass().getSimpleName(), actionEvent.actionId());
                handler.handle(actionEvent, responder);
                return;
            }
        }
        log.warn("[TelegramInteractionRouter] No handler matched for actionId={}", actionEvent.actionId());
    }

    private record TelegramInteractionResponder(
            TelegramBot bot, String chatId, String messageId, String callbackQueryId
    ) implements InteractionResponder {

        @Override
        public void replyEphemeral(String text) {
            if (bot == null || callbackQueryId == null) return;
            try {
                bot.execute(new AnswerCallbackQuery(callbackQueryId).text(text).showAlert(false));
            } catch (Exception e) {
                log.warn("[Telegram] replyEphemeral 실패: {}", e.getMessage());
            }
        }

        @Override
        public void editMessage(String newText) {
            if (bot == null || chatId == null || messageId == null) return;
            try {
                bot.execute(new EditMessageText(chatId, Integer.parseInt(messageId), newText));
            } catch (Exception e) {
                log.warn("[Telegram] editMessage 실패: {}", e.getMessage());
            }
        }

        @Override
        public void removeComponents() {
            if (bot == null || chatId == null || messageId == null) return;
            try {
                bot.execute(new EditMessageReplyMarkup(chatId, Integer.parseInt(messageId)));
            } catch (Exception e) {
                log.warn("[Telegram] removeComponents 실패: {}", e.getMessage());
            }
        }
    }
}
