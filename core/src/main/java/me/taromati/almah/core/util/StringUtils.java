package me.taromati.almah.core.util;

/**
 * 문자열 유틸리티 (null-safe)
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * 문자열을 maxLength로 잘라 "..." 접미사 추가
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || maxLength <= 0) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * "..." 접미사 없이 maxLength로만 자름 (DB 저장용 등 길이 제약 시)
     */
    public static String truncateRaw(String text, int maxLength) {
        if (text == null || maxLength <= 0) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
