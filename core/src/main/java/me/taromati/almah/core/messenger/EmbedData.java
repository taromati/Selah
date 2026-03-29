package me.taromati.almah.core.messenger;

/**
 * 플랫폼 중립 Embed 데이터.
 * Discord: MessageEmbed, Telegram: HTML formatted message fallback.
 *
 * @param description embed 본문 (마크다운 지원, Discord 기준 4096자 제한)
 */
public record EmbedData(
        String description
) {}
