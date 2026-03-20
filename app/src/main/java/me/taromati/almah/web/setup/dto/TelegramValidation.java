package me.taromati.almah.web.setup.dto;

public record TelegramValidation(boolean valid, String username, String firstName) {
    public static TelegramValidation invalid() {
        return new TelegramValidation(false, null, null);
    }
}
