package me.taromati.almah.agent.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Builder
@Data
@Entity
@Table(name = "agent_sessions")
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String channelId;

    @Column
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column
    private Integer compactionCount;

    @Column(columnDefinition = "TEXT")
    private String toolApprovals;

    /** 세션별 LLM 모델 (nullable → 프로바이더 기본 모델) */
    @Column
    private String llmModel;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUIDv7.generate();
        }
        if (this.compactionCount == null) {
            this.compactionCount = 0;
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
