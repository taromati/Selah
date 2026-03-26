package me.taromati.almah.web.setup.dto;

public record ChatDetectionStatus(
        String state,      // "idle" | "waiting" | "detected" | "timeout"
        String chatId,     // detected 시
        String chatTitle   // detected 시
) {
    public static ChatDetectionStatus idle() {
        return new ChatDetectionStatus("idle", null, null);
    }

    public static ChatDetectionStatus waiting() {
        return new ChatDetectionStatus("waiting", null, null);
    }

    public static ChatDetectionStatus detected(String chatId, String title) {
        return new ChatDetectionStatus("detected", chatId, title);
    }

    public static ChatDetectionStatus timeout() {
        return new ChatDetectionStatus("timeout", null, null);
    }
}
