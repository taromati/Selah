package me.taromati.almah.agent.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Builder
@Data
@Entity
@Table(name = "agent_routine_history", indexes = {
        @Index(name = "idx_routine_history_completed_at", columnList = "completedAt DESC")
})
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRoutineHistoryEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private LocalDateTime completedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false, length = 20)
    private String status;  // "COMPLETED" | "FAILED"

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

    public static AgentRoutineHistoryEntity of(String title, String summary, String status) {
        return AgentRoutineHistoryEntity.builder()
                .title(title)
                .summary(summary)
                .status(status)
                .completedAt(LocalDateTime.now())
                .build();
    }
}
