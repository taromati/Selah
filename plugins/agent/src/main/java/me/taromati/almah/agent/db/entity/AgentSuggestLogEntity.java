package me.taromati.almah.agent.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Builder
@Data
@Entity
@Table(name = "agent_suggest_log")
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentSuggestLogEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 20)
    private String response;  // APPROVED, DENIED, MODIFIED, NO_RESPONSE

    private LocalDateTime respondedAt;

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
