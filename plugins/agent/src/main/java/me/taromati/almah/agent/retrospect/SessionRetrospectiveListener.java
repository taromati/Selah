package me.taromati.almah.agent.retrospect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SessionRetrospectiveListener {

    private final RetrospectiveService retrospectiveService;
    private final AgentConfigProperties config;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionArchived(SessionArchivedEvent event) {
        // routine 세션은 회고 대상 아님
        if (event.channelId().startsWith("routine:")) return;

        if (!Boolean.TRUE.equals(config.getRetrospect().getEnabled())) return;

        try {
            retrospectiveService.retrospectSession(event.sessionId());
        } catch (Exception e) {
            log.warn("[SessionRetrospect] 세션 {} 회고 실패: {}", event.sessionId(), e.getMessage());
        }
    }
}
