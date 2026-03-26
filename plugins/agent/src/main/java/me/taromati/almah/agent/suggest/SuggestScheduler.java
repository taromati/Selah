package me.taromati.almah.agent.suggest;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.service.AgentBusyState;
import me.taromati.almah.agent.service.AgentSessionService;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.core.util.TimeConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SuggestScheduler {

    private final SuggestGenerator generator;
    private final SuggestHistory history;
    private final AgentConfigProperties config;
    private final AgentBusyState busyState;
    private final AgentSessionService sessionService;
    private final MessengerGatewayRegistry messengerRegistry;

    public SuggestScheduler(SuggestGenerator generator, SuggestHistory history,
                            AgentConfigProperties config,
                            AgentBusyState busyState,
                            AgentSessionService sessionService,
                            MessengerGatewayRegistry messengerRegistry) {
        this.generator = generator;
        this.history = history;
        this.config = config;
        this.busyState = busyState;
        this.sessionService = sessionService;
        this.messengerRegistry = messengerRegistry;
    }

    /**
     * 자율 제안 시도. 독립 스케줄로 실행됨.
     */
    @Scheduled(fixedDelayString = "${plugins.agent.suggest.interval-ms:3600000}")
    public void attemptSuggest() {
        // 타임아웃 처리 (게이트 전에 실행)
        expireTimedOut();

        if (!Boolean.TRUE.equals(config.getSuggest().getEnabled())) return;
        if (!isWithinActiveHours()) return;
        var suggestConfig = config.getSuggest();
        if (history.countToday() >= suggestConfig.getDailyLimit()) return;
        if (busyState != null && busyState.isAnyBusy()) return;

        // 새 게이트: PENDING 제안 대기 중이면 스킵
        if (history.hasPending()) {
            log.debug("[SuggestScheduler] PENDING 제안 대기 중 — 새 제안 스킵");
            return;
        }

        // 쿨다운 확인
        LocalDateTime last = history.lastSuggestTime();
        if (last != null) {
            long hoursSince = Duration.between(last, LocalDateTime.now()).toHours();
            if (hoursSince < suggestConfig.getCooldownHours()) return;
        }

        generator.generate();
    }

    /**
     * PENDING 상태이고 타임아웃된 제안을 EXPIRED로 전환.
     */
    private void expireTimedOut() {
        try {
            int timeout = config.getSuggest().getApprovalTimeoutMinutes();
            var expired = history.findExpired(timeout);
            for (var suggest : expired) {
                history.recordResponse(suggest.getId(), "EXPIRED");
                // Discord 메시지 버튼 비활성화
                disableButtons(suggest, "⏰ 만료됨");
                saveToSession("(제안 '" + StringUtils.truncate(suggest.getContent(), 50) + "'이 만료되었습니다)");
                log.info("[SuggestScheduler] 제안 만료: {}", suggest.getId());
            }
        } catch (Exception e) {
            log.warn("[SuggestScheduler] 타임아웃 처리 실패: {}", e.getMessage());
        }
    }

    private void disableButtons(me.taromati.almah.agent.db.entity.AgentSuggestLogEntity suggest, String statusText) {
        try {
            if (suggest.getChannelId() == null || suggest.getMessageId() == null) return;
            ChannelRef channel = ChannelRef.deserialize(suggest.getChannelId());
            if (channel == null) return;
            messengerRegistry.editMessage(channel, suggest.getMessageId(), statusText);
        } catch (Exception e) {
            log.warn("[SuggestScheduler] 버튼 비활성화 실패: {}", e.getMessage());
        }
    }

    private void saveToSession(String content) {
        try {
            if (sessionService == null) return;
            var session = sessionService.getOrCreateActiveSession(config.getChannelName());
            String sessionId = session != null ? session.getId() : null;
            sessionService.saveMessage(sessionId, "assistant", content, null, null);
        } catch (Exception e) {
            log.warn("[SuggestScheduler] 세션 기록 실패: {}", e.getMessage());
        }
    }

    boolean isWithinActiveHours() {
        var suggestConfig = config.getSuggest();
        int now = ZonedDateTime.now(TimeConstants.KST).getHour();
        return now >= suggestConfig.getActiveStartHour() && now < suggestConfig.getActiveEndHour();
    }
}
