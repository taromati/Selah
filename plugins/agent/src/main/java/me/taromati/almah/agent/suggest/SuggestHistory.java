package me.taromati.almah.agent.suggest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentSuggestLogEntity;
import me.taromati.almah.agent.db.repository.AgentSuggestLogRepository;
import me.taromati.almah.core.util.TimeConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SuggestHistory {

    private final AgentSuggestLogRepository repository;

    @Transactional("agentTransactionManager")
    public AgentSuggestLogEntity save(String content) {
        return repository.save(AgentSuggestLogEntity.builder()
                .content(content)
                .build());
    }

    @Transactional("agentTransactionManager")
    public void recordResponse(String id, String response) {
        repository.findById(id).ifPresent(logEntry -> {
            logEntry.setResponse(response);
            logEntry.setRespondedAt(LocalDateTime.now());
            repository.save(logEntry);
        });
    }

    public int countToday() {
        LocalDateTime todayStart = LocalDate.now(TimeConstants.KST)
                .atStartOfDay();
        return repository.countByCreatedAtAfter(todayStart);
    }

    public List<AgentSuggestLogEntity> getRecent() {
        return repository.findTop20ByOrderByCreatedAtDesc();
    }

    public LocalDateTime lastSuggestTime() {
        return repository.findTop20ByOrderByCreatedAtDesc().stream()
                .findFirst()
                .map(AgentSuggestLogEntity::getCreatedAt)
                .orElse(null);
    }

    /**
     * 미응답 제안이 있으면 사용자 응답으로 기록.
     * 사용자가 채팅을 보낸 시점에 호출되어, 가장 최근 미응답 제안을 "USER_REPLIED"로 마킹합니다.
     */
    @Transactional("agentTransactionManager")
    public void recordPendingResponse(String userMessage) {
        repository.findFirstByResponseIsNullOrderByCreatedAtDesc()
                .ifPresent(entry -> {
                    entry.setResponse("USER_REPLIED");
                    entry.setRespondedAt(LocalDateTime.now());
                    repository.save(entry);
                    log.debug("[SuggestHistory] Pending suggest {} marked as USER_REPLIED", entry.getId());
                });
    }
}
