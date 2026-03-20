package me.taromati.almah.core.messenger.discord;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.*;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "discord.enabled", havingValue = "true")
public class DiscordPluginRouter extends ListenerAdapter {

    private final List<PluginListener> listeners;
    private final Environment environment;

    public DiscordPluginRouter(List<PluginListener> listeners, Environment environment) {
        this.listeners = listeners;
        this.environment = environment;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        TextChannel textChannel;
        try {
            textChannel = event.getChannel().asTextChannel();
        } catch (Exception e) {
            return;
        }
        if (textChannel == null) return;

        String channelName = textChannel.getName();
        String channelId = textChannel.getId();

        for (PluginListener listener : listeners) {
            if (channelName.equals(resolveChannel(listener.getPluginName()))) {
                IncomingMessage msg = convertMessage(event, channelId, listener.needsAttachments());
                listener.onMessage(msg);
            }
        }
    }

    private IncomingMessage convertMessage(MessageReceivedEvent event, String channelId,
                                            boolean needsAttachments) {
        Message message = event.getMessage();
        boolean mentionsBot = false;
        try {
            mentionsBot = message.getMentions().isMentioned(event.getJDA().getSelfUser());
        } catch (Exception ignored) {}

        List<IncomingMessage.Attachment> attachments = List.of();
        if (needsAttachments) {
            attachments = convertAttachments(message.getAttachments());
        }

        return new IncomingMessage(
                ChannelRef.of(MessengerPlatform.DISCORD, channelId),
                event.getAuthor().getId(),
                event.getAuthor().getName(),
                event.getAuthor().isBot(),
                message.getContentRaw(),
                mentionsBot,
                attachments
        );
    }

    private List<IncomingMessage.Attachment> convertAttachments(List<Message.Attachment> jdaAttachments) {
        List<IncomingMessage.Attachment> result = new ArrayList<>();
        for (Message.Attachment att : jdaAttachments) {
            byte[] data = null;
            try {
                data = att.getProxy().download().get().readAllBytes();
            } catch (Exception e) {
                log.warn("[DiscordPluginRouter] 첨부파일 다운로드 실패: {}", att.getFileName(), e);
            }
            result.add(new IncomingMessage.Attachment(
                    att.getFileName(),
                    att.getContentType(),
                    att.isImage(),
                    data
            ));
        }
        return result;
    }

    private String resolveChannel(String pluginName) {
        String defaultChannel = environment.getProperty("plugins.notification-channel", "bot");
        return environment.getProperty(
                "plugins." + pluginName + ".channel-name",
                defaultChannel);
    }
}
