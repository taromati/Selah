package me.taromati.almah.core.messenger.discord;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "discord.enabled", havingValue = "true")
public class DiscordMessengerGateway implements MessengerGateway {

    private final ObjectProvider<DiscordInteractionRouter> interactionRouterProvider;
    private JDA jda;
    private String serverName;

    public DiscordMessengerGateway(ObjectProvider<DiscordInteractionRouter> interactionRouterProvider) {
        this.interactionRouterProvider = interactionRouterProvider;
    }

    public void initialize(JDA jda, String serverName) {
        this.jda = jda;
        this.serverName = serverName;
    }

    public boolean isInitialized() {
        return jda != null;
    }

    // ─── MessengerGateway 구현 ───

    @Override
    public MessengerPlatform getPlatform() {
        return MessengerPlatform.DISCORD;
    }

    @Override
    public void sendText(ChannelRef channel, String message) {
        if (jda == null) return;
        TextChannel textChannel = jda.getTextChannelById(channel.channelId());
        if (textChannel == null) return;
        DiscordMessageSender.sendText(textChannel, message);
    }

    @Override
    public void sendWithImages(ChannelRef channel, String message, List<byte[]> images) {
        if (jda == null) return;
        TextChannel textChannel = jda.getTextChannelById(channel.channelId());
        if (textChannel == null) return;
        DiscordMessageSender.sendWithImages(textChannel, message, images);
    }

    @Override
    public void sendWithFiles(ChannelRef channel, String message, List<FileData> files) {
        if (jda == null) return;
        TextChannel textChannel = jda.getTextChannelById(channel.channelId());
        if (textChannel == null) return;

        List<FileUpload> uploads = new ArrayList<>();
        for (FileData file : files) {
            uploads.add(FileUpload.fromData(file.data(), file.fileName()));
        }

        if (message != null && !message.isEmpty()) {
            List<String> chunks = MessageSplitter.split(message, 2000);
            for (int i = 0; i < chunks.size() - 1; i++) {
                textChannel.sendMessage(chunks.get(i)).queue();
            }
            textChannel.sendMessage(chunks.getLast()).addFiles(uploads).queue();
        } else {
            textChannel.sendFiles(uploads).queue();
        }
    }

    @Override
    public void sendDirectMessage(String userId, String message) {
        if (jda == null || userId == null) return;
        jda.retrieveUserById(userId).queue(
                user -> user.openPrivateChannel().queue(
                        ch -> ch.sendMessage(message).queue(
                                success -> log.debug("[Discord] DM sent to {}", userId),
                                error -> log.warn("[Discord] DM 전송 실패: {}", error.getMessage())
                        ),
                        error -> log.warn("[Discord] DM 채널 열기 실패: {}", error.getMessage())
                ),
                error -> log.warn("[Discord] 사용자를 찾을 수 없음: {}", userId)
        );
    }

    @Override
    public ChannelRef resolveChannel(String channelName) {
        if (jda == null) return null;
        return jda.getGuildsByName(serverName, true).stream()
                .flatMap(guild -> guild.getTextChannelsByName(channelName, true).stream())
                .findFirst()
                .map(ch -> ChannelRef.of(MessengerPlatform.DISCORD, ch.getId()))
                .orElse(null);
    }

    @Override
    public TypingHandle startTyping(ChannelRef channel) {
        if (jda == null) return TypingHandle.noop();
        TextChannel textChannel = jda.getTextChannelById(channel.channelId());
        if (textChannel == null) return TypingHandle.noop();
        return new DiscordTypingHandle(textChannel);
    }

    @Override
    public InteractiveMessageHandle sendInteractive(ChannelRef channel, InteractiveMessage interactiveMsg) {
        if (jda == null) return null;
        TextChannel textChannel = jda.getTextChannelById(channel.channelId());
        if (textChannel == null) return null;

        List<Button> buttons = interactiveMsg.actions().stream()
                .map(this::toJdaButton)
                .toList();

        Message sent = textChannel.sendMessage(interactiveMsg.text())
                .setComponents(ActionRow.of(buttons))
                .complete();

        DiscordInteractionRouter router = interactionRouterProvider.getIfAvailable();
        return new DiscordInteractiveMessage(sent.getId(), textChannel, router);
    }

    // ─── Discord 전용 메서드 ───

    public TextChannel getJdaTextChannel(String channelName) {
        if (jda == null) return null;
        return jda.getGuildsByName(serverName, true).stream()
                .flatMap(guild -> guild.getTextChannelsByName(channelName, true).stream())
                .findFirst()
                .orElse(null);
    }

    public TextChannel getJdaTextChannelById(String channelId) {
        if (jda == null) return null;
        return jda.getTextChannelById(channelId);
    }

    public VoiceChannel getVoiceChannel(String channelName) {
        if (jda == null || channelName == null) return null;
        return jda.getGuildsByName(serverName, true).stream()
                .flatMap(guild -> guild.getVoiceChannelsByName(channelName, true).stream())
                .findFirst()
                .orElse(null);
    }

    public Guild getGuild() {
        if (jda == null) return null;
        return jda.getGuildsByName(serverName, true).stream()
                .findFirst()
                .orElse(null);
    }

    // ─── 내부 유틸 ───

    private Button toJdaButton(InteractiveMessage.Action action) {
        return switch (action.style()) {
            case SUCCESS -> Button.success(action.id(), action.label());
            case DANGER -> Button.danger(action.id(), action.label());
            case PRIMARY -> Button.primary(action.id(), action.label());
        };
    }
}
