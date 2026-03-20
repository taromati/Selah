package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentRoutineHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentRoutineHistoryRepository extends JpaRepository<AgentRoutineHistoryEntity, String> {

    List<AgentRoutineHistoryEntity> findTop20ByOrderByCompletedAtDesc();

    Page<AgentRoutineHistoryEntity> findAllByOrderByCompletedAtDesc(Pageable pageable);

    @Modifying
    @Transactional("agentTransactionManager")
    void deleteByCompletedAtBefore(LocalDateTime cutoff);
}
