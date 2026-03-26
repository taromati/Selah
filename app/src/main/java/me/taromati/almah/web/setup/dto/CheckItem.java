package me.taromati.almah.web.setup.dto;

public record CheckItem(
        String name,
        CheckStatus status,
        String message
) {}
