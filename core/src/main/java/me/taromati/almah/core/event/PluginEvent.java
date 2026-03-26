package me.taromati.almah.core.event;

/**
 * 플러그인에서 발행하는 이벤트.
 * 내부 Spring Event + HTTP 와이어 포맷 겸용.
 */
public record PluginEvent(String pluginName, String message) {}
