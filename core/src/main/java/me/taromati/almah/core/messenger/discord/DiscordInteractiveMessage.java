package me.taromati.almah.core.messenger.discord;

import me.taromati.almah.core.messenger.ActionEvent;
import me.taromati.almah.core.messenger.InteractiveMessageHandle;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DiscordInteractiveMessage implements InteractiveMessageHandle {

    private final String messageId;
    private final TextChannel channel;
    private final CompletableFuture<ActionEvent> future = new CompletableFuture<>();
    private final DiscordInteractionRouter router;

    public DiscordInteractiveMessage(String messageId, TextChannel channel, DiscordInteractionRouter router) {
        this.messageId = messageId;
        this.channel = channel;
        this.router = router;
    }

    @Override
    public ActionEvent waitForAction(long timeout, TimeUnit unit) {
        if (router != null) {
            router.registerTemporaryHandle(messageId, this);
        }
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            if (router != null) router.removeTemporaryHandle(messageId);
            return null;
        } catch (Exception e) {
            if (router != null) router.removeTemporaryHandle(messageId);
            return null;
        }
    }

    @Override
    public void editText(String newText) {
        channel.editMessageById(messageId, newText)
                .setComponents()
                .queue();
    }

    @Override
    public CompletableFuture<ActionEvent> getFuture() {
        return future;
    }
}
