package me.taromati.almah.core.util;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * UUIDv7 생성 유틸리티
 * - 시간 기반 정렬 가능한 UUID
 * - RFC 9562 표준
 */
public final class UUIDv7 {

    private UUIDv7() {
    }

    /**
     * UUIDv7 생성 (하이픈 포함, 36자)
     * 예: 018f6b1a-5b3c-7d4e-8f9a-1b2c3d4e5f6a
     */
    public static String generate() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }

    /**
     * UUIDv7 생성 (하이픈 제외, 32자)
     * 예: 018f6b1a5b3c7d4e8f9a1b2c3d4e5f6a
     */
    public static String generateCompact() {
        return UuidCreator.getTimeOrderedEpoch().toString().replace("-", "");
    }
}
