package me.taromati.almah.agent.service;

import lombok.experimental.UtilityClass;
import me.taromati.almah.core.util.TimeConstants;
import me.taromati.almah.core.util.TimeParser;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * cron 도구의 {@code when} 필드를 자동 감지하여 (scheduleType, scheduleValue)로 변환.
 */
@UtilityClass
public class ScheduleParser {

    public record ParsedSchedule(String type, String value) {}

    private static final Pattern INTERVAL_SUFFIX = Pattern.compile("^(\\d+)\\s*[분mM]$");
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * when 필드를 자동 감지하여 scheduleType/scheduleValue로 변환.
     * <p>
     * 감지 우선순위:
     * <ol>
     *   <li>ISO 8601 → at 타입</li>
     *   <li>cron 표현식 → cron 타입 (5필드→6필드 정규화)</li>
     *   <li>시각 표현 (한국어/콜론) → at 타입 (ISO 8601 변환)</li>
     *   <li>숫자+접미사("30분", "30m") 또는 숫자(24+) → every 타입</li>
     *   <li>숫자(0~23) → 시각으로 해석 → at 타입</li>
     * </ol>
     *
     * @param when     사용자 입력
     * @param timezone 타임존 ID (null이면 KST)
     * @return ParsedSchedule
     * @throws IllegalArgumentException 파싱 불가 시
     */
    public static ParsedSchedule parse(String when, String timezone) {
        if (when == null || when.isBlank()) {
            throw new IllegalArgumentException("when 값이 비어있습니다.");
        }

        String trimmed = when.trim();
        ZoneId tz = timezone != null ? ZoneId.of(timezone) : TimeConstants.KST;

        // 1. ISO 8601
        try {
            OffsetDateTime odt = OffsetDateTime.parse(trimmed);
            return new ParsedSchedule("at", odt.format(ISO_OFFSET));
        } catch (Exception ignored) {}

        // 2. cron 표현식 (공백 구분 5~6 토큰)
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length >= 5 && tokens.length <= 6 && looksLikeCron(tokens)) {
            String normalized = normalizeCron(trimmed, tokens);
            try {
                CronExpression.parse(normalized);
                return new ParsedSchedule("cron", normalized);
            } catch (IllegalArgumentException ignored) {}
        }

        // 3. 시각 표현 (한국어/콜론) → at 타입
        Optional<LocalTime> timeOfDay = TimeParser.parseTimeOfDay(trimmed);
        if (timeOfDay.isPresent()) {
            return toAtSchedule(timeOfDay.get(), tz);
        }

        // 4. 숫자 + 접미사 ("30분", "30m") → every
        Matcher intervalMatcher = INTERVAL_SUFFIX.matcher(trimmed);
        if (intervalMatcher.matches()) {
            return new ParsedSchedule("every", intervalMatcher.group(1));
        }

        // 5. 순수 숫자
        if (trimmed.matches("\\d+")) {
            int num = Integer.parseInt(trimmed);
            if (num >= 24) {
                // 24 이상 → 분 간격
                return new ParsedSchedule("every", String.valueOf(num));
            }
            // 0~23 → 시각으로 해석
            return toAtSchedule(LocalTime.of(num, 0), tz);
        }

        throw new IllegalArgumentException("인식할 수 없는 스케줄 형식: " + when);
    }

    private static ParsedSchedule toAtSchedule(LocalTime time, ZoneId tz) {
        ZonedDateTime now = ZonedDateTime.now(tz);
        ZonedDateTime target = now.withHour(time.getHour()).withMinute(time.getMinute())
                .withSecond(0).withNano(0);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        return new ParsedSchedule("at", target.toOffsetDateTime().format(ISO_OFFSET));
    }

    /**
     * cron 토큰이 숫자/와일드카드/슬래시/하이픈/콤마로 구성되어 있는지 간이 체크.
     */
    private static boolean looksLikeCron(String[] tokens) {
        Pattern cronToken = Pattern.compile("[0-9*/?\\-,]+");
        // 첫 5개(또는 6개) 토큰이 모두 cron 패턴이어야 함
        int count = Math.min(tokens.length, 6);
        for (int i = 0; i < count; i++) {
            if (!cronToken.matcher(tokens[i]).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 5필드 cron → 6필드 변환 (초 필드 추가). 이미 6필드이면 그대로.
     */
    private static String normalizeCron(String raw, String[] tokens) {
        if (tokens.length == 5) {
            return "0 " + raw.trim();
        }
        return raw.trim();
    }
}
