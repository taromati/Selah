package me.taromati.almah.web.setup.dto;

public record LlmTestRequest(String provider, String baseUrl, String apiKey, String model, String cliPath) {}
