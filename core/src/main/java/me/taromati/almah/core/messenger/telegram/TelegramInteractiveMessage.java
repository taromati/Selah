package me.taromati.almah.core.messenger.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.EditMessageText;
import me.taromati.almah.core.messenger.ActionEvent;
import me.taromati.almah.core.messenger.InteractiveMessageHandle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TelegramInteractiveMessage implements InteractiveMessageHandle {

    private final String chatId;
    private final int messageId;
    private final TelegramBot bot;
    private final TelegramInteractionRouter router;
    private final CompletableFuture<ActionEvent> future = new CompletableFuture<>();

    public TelegramInteractiveMessage(String chatId, int messageId, TelegramBot bot,
                                       TelegramInteractionRouter router) {
        this.chatId = chatId;
        this.messageId = messageId;
        this.bot = bot;
        this.router = router;
    }

    @Override
    public ActionEvent waitForAction(long timeout, TimeUnit unit) {
        if (router != null) {
            router.registerTemporaryHandle(String.valueOf(messageId), this);
        }
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            if (router != null) router.removeTemporaryHandle(String.valueOf(messageId));
            return null;
        } catch (Exception e) {
            if (router != null) router.removeTemporaryHandle(String.valueOf(messageId));
            return null;
        }
    }

    @Override
    public void editText(String newText) {
        bot.execute(new EditMessageText(chatId, messageId, newText));
    }

    @Override
    public CompletableFuture<ActionEvent> getFuture() {
        return future;
    }

    @Override
    public String getMessageId() {
        return String.valueOf(messageId);
    }
}
