package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentScheduledJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AgentScheduledJobRepository extends JpaRepository<AgentScheduledJobEntity, String> {

    List<AgentScheduledJobEntity> findByEnabledTrueAndNextRunAtBefore(Instant now);

    List<AgentScheduledJobEntity> findByChannelId(String channelId);

    List<AgentScheduledJobEntity> findAllByOrderByCreatedAtDesc();
}
