package me.taromati.almah.core.voice;

import java.util.List;

/**
 * 음성 채팅 처리 결과 DTO
 * ChatResult(aichat)를 core에서 참조할 수 없으므로 경량 DTO로 분리
 */
public record VoiceChatResult(String text, List<byte[]> images) {

    public static VoiceChatResult text(String text) {
        return new VoiceChatResult(text, List.of());
    }

    public static VoiceChatResult withImages(String text, List<byte[]> images) {
        return new VoiceChatResult(text, images != null ? images : List.of());
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
