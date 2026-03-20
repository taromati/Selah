package me.taromati.almah.agent.service;

import lombok.experimental.UtilityClass;
import me.taromati.almah.core.util.TimeConstants;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 스케줄 계산 유틸리티.
 * at(일회성), every(반복 간격), cron(표현식) 타입의 nextRunAt 계산.
 */
@UtilityClass
public class ScheduleCalculator {

    /**
     * 잡 생성 시 최초 nextRunAt 계산.
     *
     * @param scheduleType  "at" | "every" | "cron"
     * @param scheduleValue ISO timestamp | 분 | cron 6필드
     * @param timezone      타임존 (null이면 KST)
     * @return 최초 실행 시각
     */
    public static Instant calculateInitialRun(String scheduleType, String scheduleValue,
                                               String timezone) {
        return switch (scheduleType) {
            case "at" -> OffsetDateTime.parse(scheduleValue).toInstant();
            case "every" -> Instant.now().plus(Duration.ofMinutes(Long.parseLong(scheduleValue)));
            case "cron" -> calculateNextRun("cron", scheduleValue, timezone, Instant.now());
            default -> throw new IllegalArgumentException("Unknown schedule type: " + scheduleType);
        };
    }

    /**
     * 실행 후 다음 nextRunAt 계산.
     *
     * @param scheduleType  "at" | "every" | "cron"
     * @param scheduleValue ISO timestamp | 분 | cron 6필드
     * @param timezone      타임존 (null이면 KST)
     * @param now           기준 시점
     * @return 다음 실행 시각 (at 타입이면 null = one-shot 완료)
     */
    public static Instant calculateNextRun(String scheduleType, String scheduleValue,
                                            String timezone, Instant now) {
        return switch (scheduleType) {
            case "at" -> null;  // 일회성 → 완료
            case "every" -> now.plus(Duration.ofMinutes(Long.parseLong(scheduleValue)));
            case "cron" -> {
                var cron = CronExpression.parse(scheduleValue);
                var tz = timezone != null ? ZoneId.of(timezone) : TimeConstants.KST;
                var next = cron.next(LocalDateTime.ofInstant(now, tz));
                yield next != null ? next.atZone(tz).toInstant() : null;
            }
            default -> null;
        };
    }
}
