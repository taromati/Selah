package me.taromati.almah.agent.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Builder
@Data
@Entity
@Table(name = "agent_task_items", indexes = {
        @Index(name = "idx_task_status", columnList = "status"),
        @Index(name = "idx_task_created", columnList = "createdAt")
})
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentTaskItemEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String originalRequest;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(columnDefinition = "TEXT")
    private String progress;

    @Builder.Default
    @Column(nullable = false)
    private Integer retryCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer maxRetries = 5;

    @Column(columnDefinition = "TEXT")
    private String actionScope;

    @Column(columnDefinition = "TEXT")
    private String executionLog;

    @Column(columnDefinition = "TEXT")
    private String rejectedTools;

    @Column(length = 36)
    private String approvalRequestId;

    private LocalDateTime approvalRequestedAt;

    private LocalDateTime approvalRespondedAt;

    @Column(length = 20)
    private String approvalResponse;

    @Column(length = 64)
    private String escalationMessageId;

    @Column(length = 128)
    private String escalationChannelId;

    private LocalDateTime completedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUIDv7.generate();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = this.createdAt;
        }
        if (this.status == null) {
            this.status = "PENDING";
        }
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
        if (this.maxRetries == null) {
            this.maxRetries = 5;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
