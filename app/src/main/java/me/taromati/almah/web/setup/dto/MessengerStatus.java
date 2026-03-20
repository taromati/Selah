package me.taromati.almah.web.setup.dto;

public record MessengerStatus(
        boolean configured,
        boolean connected,
        String displayName
) {}
