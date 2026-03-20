package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, String> {
    Optional<AgentSessionEntity> findByChannelIdAndActiveTrue(String channelId);

    List<AgentSessionEntity> findByChannelIdOrderByUpdatedAtDesc(String channelId);

    List<AgentSessionEntity> findByChannelIdAndActiveFalseOrderByUpdatedAtDesc(String channelId);

    List<AgentSessionEntity> findAllByOrderByUpdatedAtDesc();
}
