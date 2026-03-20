package me.taromati.almah.core.util;

import java.time.Duration;

/**
 * 시간 포맷 유틸리티
 * 시간 간격을 사용자 친화적인 한국어 문자열로 변환
 */
public final class TimeFormatUtils {

    private TimeFormatUtils() {}

    /**
     * 시간 간격을 메타데이터 마커로 포맷.
     * LLM이 모방할 수 없는 형식 사용.
     * 예: "[+2h30m]", "[+30m]", "[+1d5h]"
     */
    public static String formatTimeGapMarker(Duration gap) {
        long totalMinutes = gap.toMinutes();
        if (totalMinutes < 60) {
            return String.format("[+%dm]", totalMinutes);
        }
        long hours = gap.toHours();
        long remainMinutes = totalMinutes - hours * 60;
        if (hours < 24) {
            return remainMinutes > 0
                    ? String.format("[+%dh%dm]", hours, remainMinutes)
                    : String.format("[+%dh]", hours);
        }
        long days = hours / 24;
        long remainHours = hours - days * 24;
        return remainHours > 0
                ? String.format("[+%dd%dh]", days, remainHours)
                : String.format("[+%dd]", days);
    }

    /**
     * Duration을 읽기 쉬운 한국어로 포맷
     * 예: "3시간 27분", "1분 미만", "2일 5시간"
     */
    public static String formatDurationKorean(Duration duration) {
        long totalMinutes = duration.toMinutes();
        if (totalMinutes < 1) {
            return "1분 미만";
        }
        if (totalMinutes < 60) {
            return totalMinutes + "분";
        }
        long hours = duration.toHours();
        long minutes = totalMinutes - hours * 60;
        if (hours < 24) {
            return minutes > 0 ? String.format("%d시간 %d분", hours, minutes) : hours + "시간";
        }
        long days = hours / 24;
        long remainHours = hours - days * 24;
        return remainHours > 0 ? String.format("%d일 %d시간", days, remainHours) : days + "일";
    }
}
