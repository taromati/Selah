package me.taromati.almah.web.setup.dto;

public record DiscordValidation(boolean valid, String botName) {
    public static DiscordValidation invalid() {
        return new DiscordValidation(false, null);
    }
}
