package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentTaskItemRepository extends JpaRepository<AgentTaskItemEntity, String> {
    List<AgentTaskItemEntity> findByStatusOrderByCreatedAtAsc(String status);
    List<AgentTaskItemEntity> findByStatusInOrderByCreatedAtAsc(List<String> statuses);
    List<AgentTaskItemEntity> findByStatusNotInOrderByCreatedAtDesc(List<String> statuses);
    List<AgentTaskItemEntity> findByStatusInAndCompletedAtBefore(List<String> statuses, LocalDateTime before);
    int countByStatus(String status);
    boolean existsByTitleAndStatusIn(String title, List<String> statuses);
    Optional<AgentTaskItemEntity> findFirstByIdStartingWith(String idPrefix);
}
