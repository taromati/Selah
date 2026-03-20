package me.taromati.almah.web.setup.dto;

public record ServiceStatus(
        boolean configured,
        boolean connected,
        String displayName
) {}
