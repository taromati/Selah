package me.taromati.almah.core.messenger.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendChatAction;
import me.taromati.almah.core.messenger.TypingHandle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TelegramTypingHandle implements TypingHandle {

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "telegram-typing");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledFuture<?> task;

    public TelegramTypingHandle(TelegramBot bot, String chatId) {
        bot.execute(new SendChatAction(chatId, "typing"));
        this.task = SCHEDULER.scheduleAtFixedRate(
                () -> bot.execute(new SendChatAction(chatId, "typing")),
                5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
