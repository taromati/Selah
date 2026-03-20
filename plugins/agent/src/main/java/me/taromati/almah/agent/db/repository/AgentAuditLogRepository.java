package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLogEntity, String> {
    List<AgentAuditLogEntity> findByTaskItemIdOrderByCreatedAtDesc(String taskItemId);
    List<AgentAuditLogEntity> findTop100ByOrderByCreatedAtDesc();
}
