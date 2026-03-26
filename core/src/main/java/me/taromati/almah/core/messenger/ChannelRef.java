package me.taromati.almah.core.messenger;

public record ChannelRef(MessengerPlatform platform, String channelId) {

    public static ChannelRef of(MessengerPlatform platform, String channelId) {
        return new ChannelRef(platform, channelId);
    }

    /** "DISCORD:channelId" 형식으로 직렬화 */
    public String serialize() {
        return platform.name() + ":" + channelId;
    }

    /** "DISCORD:channelId" 형식에서 역직렬화. 잘못된 형식이면 null 반환. */
    public static ChannelRef deserialize(String serialized) {
        if (serialized == null) return null;
        int idx = serialized.indexOf(':');
        if (idx < 0) return null;
        try {
            MessengerPlatform p = MessengerPlatform.valueOf(serialized.substring(0, idx));
            return new ChannelRef(p, serialized.substring(idx + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
