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
}
