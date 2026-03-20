package me.taromati.almah.web.setup.dto;

public record SystemStatus(
        MessengerStatus discord,
        MessengerStatus telegram,
        ServiceStatus llm,
        ServiceStatus embedding,
        ServiceStatus searxng,
        boolean serviceRegistered,
        DoctorSummary doctor
) {}
