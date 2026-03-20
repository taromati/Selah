package me.taromati.almah.core.messenger.discord;

import me.taromati.almah.core.messenger.TypingHandle;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DiscordTypingHandle implements TypingHandle {

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "discord-typing");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledFuture<?> task;

    public DiscordTypingHandle(TextChannel channel) {
        channel.sendTyping().queue();
        this.task = SCHEDULER.scheduleAtFixedRate(
                () -> channel.sendTyping().queue(),
                5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
