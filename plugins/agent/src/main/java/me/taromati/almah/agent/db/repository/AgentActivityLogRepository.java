package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentActivityLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentActivityLogRepository extends JpaRepository<AgentActivityLogEntity, String> {

    List<AgentActivityLogEntity> findByChannelIdOrderByCreatedAtDesc(String channelId);

    Page<AgentActivityLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AgentActivityLogEntity> findByResultTextContainingOrJobNameContaining(
            String resultTextKeyword, String jobNameKeyword, Pageable pageable);

    Page<AgentActivityLogEntity> findByActivityTypeOrderByCreatedAtDesc(String activityType, Pageable pageable);

    Page<AgentActivityLogEntity> findByActivityTypeAndResultTextContainingOrActivityTypeAndJobNameContaining(
            String activityType1, String resultTextKeyword,
            String activityType2, String jobNameKeyword,
            Pageable pageable);
}
