package me.taromati.almah.core.messenger;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MessengerGatewayRegistry {

    private final Map<MessengerPlatform, MessengerGateway> gateways;

    public MessengerGatewayRegistry(List<MessengerGateway> gatewayList) {
        this.gateways = gatewayList.stream()
                .collect(Collectors.toMap(MessengerGateway::getPlatform, Function.identity()));
    }

    // ─── 단일 플랫폼 발신 ───

    public void sendText(ChannelRef channel, String message) {
        var gw = gateways.get(channel.platform());
        if (gw != null) gw.sendText(channel, message);
    }

    public void sendWithImages(ChannelRef channel, String message, List<byte[]> images) {
        var gw = gateways.get(channel.platform());
        if (gw != null) gw.sendWithImages(channel, message, images);
    }

    public void sendWithFiles(ChannelRef channel, String message, List<FileData> files) {
        var gw = gateways.get(channel.platform());
        if (gw != null) gw.sendWithFiles(channel, message, files);
    }

    public TypingHandle startTyping(ChannelRef channel) {
        var gw = gateways.get(channel.platform());
        if (gw != null) return gw.startTyping(channel);
        return TypingHandle.noop();
    }

    public InteractiveMessageHandle sendInteractive(ChannelRef channel, InteractiveMessage message) {
        var gw = gateways.get(channel.platform());
        if (gw != null) return gw.sendInteractive(channel, message);
        return null;
    }

    public String sendTextAndGetId(ChannelRef channel, String message) {
        var gw = gateways.get(channel.platform());
        if (gw != null) return gw.sendTextAndGetId(channel, message);
        return null;
    }

    public void editMessage(ChannelRef channel, String messageId, String newText) {
        var gw = gateways.get(channel.platform());
        if (gw != null) gw.editMessage(channel, messageId, newText);
    }

    public void deleteMessage(ChannelRef channel, String messageId) {
        var gw = gateways.get(channel.platform());
        if (gw != null) gw.deleteMessage(channel, messageId);
    }

    public void sendWithEmbed(ChannelRef channel, String text, EmbedData embed) {
        var gw = gateways.get(channel.platform());
        if (gw != null) gw.sendWithEmbed(channel, text, embed);
    }

    // ─── 브로드캐스트 발신 ───

    public void broadcastText(String channelName, String message) {
        for (MessengerGateway gw : gateways.values()) {
            ChannelRef ch = gw.resolveChannel(channelName);
            if (ch != null) gw.sendText(ch, message);
        }
    }

    public void broadcastWithImages(String channelName, String message, List<byte[]> images) {
        for (MessengerGateway gw : gateways.values()) {
            ChannelRef ch = gw.resolveChannel(channelName);
            if (ch != null) gw.sendWithImages(ch, message, images);
        }
    }

    /**
     * 모든 게이트웨이에 인터랙티브 메시지 발송.
     * 첫 번째 성공한 발송의 handle + channel을 반환 (messageId 저장용).
     */
    public BroadcastResult broadcastInteractive(String channelName, InteractiveMessage message) {
        BroadcastResult result = null;
        for (MessengerGateway gw : gateways.values()) {
            ChannelRef ch = gw.resolveChannel(channelName);
            if (ch != null) {
                InteractiveMessageHandle handle = gw.sendInteractive(ch, message);
                if (result == null && handle != null) {
                    result = new BroadcastResult(ch, handle);
                }
            }
        }
        return result;
    }

    public record BroadcastResult(ChannelRef channel, InteractiveMessageHandle handle) {}

    // ─── 게이트웨이 직접 접근 ───

    public MessengerGateway getGateway(MessengerPlatform platform) {
        return gateways.get(platform);
    }

    public List<MessengerGateway> getAllGateways() {
        return List.copyOf(gateways.values());
    }
}
