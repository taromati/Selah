package me.taromati.almah.llm.client;

import lombok.extern.slf4j.Slf4j;

/**
 * 고정 간격 Rate Limiter.
 * 호출 간 최소 간격을 보장하여 RPM 제한을 준수합니다.
 * synchronized로 직렬화 — 동시 호출 시 선착순 대기, burst 불가.
 */
@Slf4j
public class LlmRateLimiter {

    private final long minIntervalMs;
    private long lastCallTimeMs = 0;

    public LlmRateLimiter(int maxPerMinute) {
        this.minIntervalMs = 60_000L / maxPerMinute;
    }

    public synchronized void acquire() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = minIntervalMs - (now - lastCallTimeMs);
        if (wait > 0) {
            log.debug("[RateLimiter] {}ms 대기", wait);
            Thread.sleep(wait);
        }
        lastCallTimeMs = System.currentTimeMillis();
    }
}
