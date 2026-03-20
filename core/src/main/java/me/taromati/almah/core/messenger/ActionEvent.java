package me.taromati.almah.core.messenger;

public record ActionEvent(
        String actionId,
        String userId,
        String messageId,
        ChannelRef channel
) {}
