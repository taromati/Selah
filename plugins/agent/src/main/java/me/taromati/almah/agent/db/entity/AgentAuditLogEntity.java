package me.taromati.almah.agent.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Builder
@Data
@Entity
@Table(name = "agent_audit_log", indexes = {
        @Index(name = "idx_audit_task", columnList = "taskItemId"),
        @Index(name = "idx_audit_created", columnList = "createdAt")
})
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentAuditLogEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 36)
    private String taskItemId;

    @Column(length = 36)
    private String sessionId;

    @Column(nullable = false, length = 100)
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String arguments;

    @Column(columnDefinition = "TEXT")
    private String resultSummary;

    @Column(length = 20)
    private String scopeVerdict;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUIDv7.generate();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
