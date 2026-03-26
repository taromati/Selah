package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentSuggestLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentSuggestLogRepository extends JpaRepository<AgentSuggestLogEntity, String> {

    List<AgentSuggestLogEntity> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime after);

    int countByCreatedAtAfter(LocalDateTime after);

    List<AgentSuggestLogEntity> findTop20ByOrderByCreatedAtDesc();

    Optional<AgentSuggestLogEntity> findFirstByResponseIsNullOrderByCreatedAtDesc();

    boolean existsByResponse(String response);

    List<AgentSuggestLogEntity> findByResponseAndCreatedAtBefore(String response, LocalDateTime cutoff);

    Optional<AgentSuggestLogEntity> findFirstByIdStartingWith(String idPrefix);

    /**
     * 미응답(response=null) 또는 PENDING 제안 중 가장 최근 것 조회 (O-7).
     * 사용자가 채팅으로 응답했을 때 USER_REPLIED로 전환 대상.
     */
    @Query("SELECT e FROM AgentSuggestLogEntity e WHERE e.response IS NULL OR e.response = 'PENDING' ORDER BY e.createdAt DESC LIMIT 1")
    Optional<AgentSuggestLogEntity> findFirstPendingOrNull();
}
