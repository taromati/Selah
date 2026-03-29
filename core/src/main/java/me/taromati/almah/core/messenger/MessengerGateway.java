package me.taromati.almah.core.messenger;

import java.util.List;

public interface MessengerGateway {

    MessengerPlatform getPlatform();

    void sendText(ChannelRef channel, String message);

    void sendWithImages(ChannelRef channel, String message, List<byte[]> images);

    void sendWithFiles(ChannelRef channel, String message, List<FileData> files);

    void sendDirectMessage(String userId, String message);

    ChannelRef resolveChannel(String channelName);

    TypingHandle startTyping(ChannelRef channel);

    InteractiveMessageHandle sendInteractive(ChannelRef channel, InteractiveMessage message);

    default void editMessage(ChannelRef channel, String messageId, String newText) {}

    default void deleteMessage(ChannelRef channel, String messageId) {}

    /**
     * Embed 메시지 전송. Discord: MessageEmbed, Telegram: formatted text fallback.
     *
     * @param channel 대상 채널
     * @param text embed 앞에 붙는 일반 텍스트 (nullable)
     * @param embed embed 데이터
     */
    default String sendTextAndGetId(ChannelRef channel, String message) {
        sendText(channel, message);
        return null;
    }

    default void sendWithEmbed(ChannelRef channel, String text, EmbedData embed) {
        // 기본 구현: embed description을 텍스트에 이어붙여 전송
        String combined = (text != null && !text.isBlank() ? text + "\n\n" : "") + embed.description();
        sendText(channel, combined);
    }
}
