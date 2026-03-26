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
import java.util.Optional;

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

    /**
     * 제안 저장 (response 상태 포함).
     * @return 저장된 엔티티의 ID
     */
    @Transactional("agentTransactionManager")
    public String save(String content, String response, String passedActions) {
        return save(content, response, passedActions, null);
    }

    /**
     * 제안 저장 (response + category 포함).
     * @return 저장된 엔티티의 ID
     */
    @Transactional("agentTransactionManager")
    public String save(String content, String response, String passedActions, String category) {
        var entity = repository.save(AgentSuggestLogEntity.builder()
                .content(content)
                .response(response)
                .passedActions(passedActions)
                .category(category)
                .build());
        return entity.getId();
    }

    /**
     * 제안 저장 (response 상태만, passedActions 없음).
     */
    @Transactional("agentTransactionManager")
    public void save(String content, String response) {
        repository.save(AgentSuggestLogEntity.builder()
                .content(content)
                .response(response)
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
     * PENDING 상태 제안이 있는지 확인 (S05: 새 제안 스킵 게이트).
     */
    public boolean hasPending() {
        return repository.existsByResponse("PENDING");
    }

    /**
     * PENDING 상태이고 타임아웃된 제안 조회 (S04: 승인 타임아웃).
     */
    public List<AgentSuggestLogEntity> findExpired(int timeoutMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        return repository.findByResponseAndCreatedAtBefore("PENDING", cutoff);
    }

    /**
     * ID prefix로 제안 조회 (버튼 이벤트에서 사용).
     */
    public Optional<AgentSuggestLogEntity> findByIdPrefix(String idPrefix) {
        return repository.findFirstByIdStartingWith(idPrefix);
    }

    /**
     * 미응답 제안이 있으면 사용자 응답으로 기록.
     * 사용자가 채팅을 보낸 시점에 호출되어, 가장 최근 미응답 제안을 "USER_REPLIED"로 마킹합니다.
     */
    /**
     * 메시지 ID 저장 (버튼 비활성화용).
     */
    @Transactional("agentTransactionManager")
    public void updateMessageInfo(String id, String channelId, String messageId) {
        repository.findById(id).ifPresent(entry -> {
            entry.setChannelId(channelId);
            entry.setMessageId(messageId);
            repository.save(entry);
        });
    }

    @Transactional("agentTransactionManager")
    public void recordPendingResponse(String userMessage) {
        repository.findFirstPendingOrNull()
                .ifPresent(entry -> {
                    entry.setResponse("USER_REPLIED");
                    entry.setRespondedAt(LocalDateTime.now());
                    repository.save(entry);
                    log.debug("[SuggestHistory] Pending suggest {} marked as USER_REPLIED", entry.getId());
                });
    }
}
