package me.taromati.almah.core.messenger;

public record ChannelRef(MessengerPlatform platform, String channelId) {

    public static ChannelRef of(MessengerPlatform platform, String channelId) {
        return new ChannelRef(platform, channelId);
    }
}
