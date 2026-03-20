package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentSuggestLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentSuggestLogRepository extends JpaRepository<AgentSuggestLogEntity, String> {

    List<AgentSuggestLogEntity> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime after);

    int countByCreatedAtAfter(LocalDateTime after);

    List<AgentSuggestLogEntity> findTop20ByOrderByCreatedAtDesc();

    Optional<AgentSuggestLogEntity> findFirstByResponseIsNullOrderByCreatedAtDesc();
}
