package me.taromati.almah.core.util;

import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자연어 시간 문자열 파싱 유틸리티.
 * aichat(TimingJudgmentService)과 agent(ScheduleParser) 공용.
 */
@UtilityClass
public class TimeParser {

    // "14시 30분", "14시30분", "14시"
    private static final Pattern KOREAN_TIME = Pattern.compile("(\\d{1,2})시\\s*(\\d{1,2})?분?");
    // "14:30", "20:15"
    private static final Pattern COLON_TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");

    /**
     * 자연어 시간 문자열 → LocalTime 파싱.
     * "14시 30분", "14:30", "21시" 등 지원.
     *
     * @return 파싱된 시각, 매칭 실패 시 empty
     */
    public static Optional<LocalTime> parseTimeOfDay(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        // 1. 한국어 형식: "14시 30분", "14시"
        Matcher koreanMatcher = KOREAN_TIME.matcher(input);
        if (koreanMatcher.find()) {
            int hour = Integer.parseInt(koreanMatcher.group(1));
            String minuteStr = koreanMatcher.group(2);
            int minute = (minuteStr != null) ? Integer.parseInt(minuteStr) : 0;
            if (isValidTime(hour, minute)) {
                return Optional.of(LocalTime.of(hour, minute));
            }
            return Optional.empty();
        }

        // 2. 콜론 형식: "14:30"
        Matcher colonMatcher = COLON_TIME.matcher(input);
        if (colonMatcher.find()) {
            int hour = Integer.parseInt(colonMatcher.group(1));
            int minute = Integer.parseInt(colonMatcher.group(2));
            if (isValidTime(hour, minute)) {
                return Optional.of(LocalTime.of(hour, minute));
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    /**
     * 지정 시각까지 남은 분 계산.
     * 과거 시각이면 다음 날로 자동 전환.
     *
     * @return 분 단위 (유효하지 않은 시/분이면 -1)
     */
    public static int minutesUntil(int hour, int minute, ZonedDateTime now) {
        if (!isValidTime(hour, minute)) {
            return -1;
        }
        ZonedDateTime target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        return (int) Duration.between(now, target).toMinutes();
    }

    private static boolean isValidTime(int hour, int minute) {
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }
}
