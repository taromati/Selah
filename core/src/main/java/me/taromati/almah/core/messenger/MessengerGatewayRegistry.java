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

    // ─── 게이트웨이 직접 접근 ───

    public MessengerGateway getGateway(MessengerPlatform platform) {
        return gateways.get(platform);
    }

    public List<MessengerGateway> getAllGateways() {
        return List.copyOf(gateways.values());
    }
}
