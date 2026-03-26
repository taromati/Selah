package me.taromati.almah.core.messenger.discord;

import me.taromati.almah.core.messenger.*;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "discord.enabled", havingValue = "true")
public class DiscordInteractionRouter extends ListenerAdapter {

    private final List<InteractionHandler> handlers;
    private final ConcurrentHashMap<String, DiscordInteractiveMessage> temporaryHandles = new ConcurrentHashMap<>();

    public DiscordInteractionRouter(List<InteractionHandler> handlers) {
        this.handlers = handlers;
    }

    public void registerTemporaryHandle(String messageId, DiscordInteractiveMessage handle) {
        temporaryHandles.put(messageId, handle);
    }

    public void removeTemporaryHandle(String messageId) {
        temporaryHandles.remove(messageId);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String messageId = event.getMessageId();
        String actionId = event.getComponentId();
        String userId = event.getUser().getId();
        String channelId = event.getChannel().getId();
        ChannelRef channel = ChannelRef.of(MessengerPlatform.DISCORD, channelId);
        ActionEvent actionEvent = new ActionEvent(actionId, userId, messageId, channel);

        // 1. 임시 핸들 매칭
        var handle = temporaryHandles.remove(messageId);
        if (handle != null) {
            handle.getFuture().complete(actionEvent);
            return;
        }

        // 2. 영구 핸들러 매칭
        InteractionResponder responder = new DiscordInteractionResponder(event);
        for (InteractionHandler handler : handlers) {
            if (actionId.startsWith(handler.getActionIdPrefix())) {
                handler.handle(actionEvent, responder);
                return;
            }
        }
    }

    private record DiscordInteractionResponder(
            ButtonInteractionEvent event) implements InteractionResponder {

        @Override
        public void replyEphemeral(String text) {
            event.reply(text).setEphemeral(true).queue();
        }

        @Override
        public void editMessage(String newText) {
            event.editMessage(newText).setComponents().queue();
        }

        @Override
        public void removeComponents() {
            event.getMessage().editMessageComponents().queue();
        }
    }
}
