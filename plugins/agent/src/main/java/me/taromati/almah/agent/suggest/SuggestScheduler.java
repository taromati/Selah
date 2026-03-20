package me.taromati.almah.agent.suggest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.service.AgentBusyState;
import me.taromati.almah.core.util.TimeConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SuggestScheduler {

    private final SuggestGenerator generator;
    private final SuggestHistory history;
    private final AgentConfigProperties config;

    @Autowired(required = false)
    private AgentBusyState busyState;

    /**
     * 자율 제안 시도. 독립 스케줄로 실행됨.
     */
    @Scheduled(fixedDelayString = "${plugins.agent.suggest.interval-ms:3600000}")
    public void attemptSuggest() {
        if (!Boolean.TRUE.equals(config.getSuggest().getEnabled())) return;
        if (!isWithinActiveHours()) return;
        var suggestConfig = config.getSuggest();
        if (history.countToday() >= suggestConfig.getDailyLimit()) return;
        if (busyState != null && busyState.isAnyBusy()) return;

        // 쿨다운 확인
        LocalDateTime last = history.lastSuggestTime();
        if (last != null) {
            long hoursSince = Duration.between(last, LocalDateTime.now()).toHours();
            if (hoursSince < suggestConfig.getCooldownHours()) return;
        }

        generator.generate();
    }

    boolean isWithinActiveHours() {
        var suggestConfig = config.getSuggest();
        int now = ZonedDateTime.now(TimeConstants.KST).getHour();
        return now >= suggestConfig.getActiveStartHour() && now < suggestConfig.getActiveEndHour();
    }
}
