package me.taromati.almah.agent.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Builder
@Data
@Entity
@Table(name = "agent_activity_log")
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentActivityLogEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 20)
    private String activityType;        // "cron" | "routine"

    @Column(nullable = false, length = 100)
    private String jobName;             // 잡 이름 또는 "Routine"

    @Column(nullable = false, length = 20)
    private String channelId;

    @Column(nullable = false, length = 20)
    private String executionType;       // "message" | "agent-turn" | "routine"

    @Column(nullable = false, length = 20)
    private String status;              // "success" | "error" | "skipped"

    @Column(columnDefinition = "TEXT")
    private String resultText;

    @Column(columnDefinition = "TEXT")
    private String toolsUsed;           // JSON: ["exec","web_fetch"]

    @Column(length = 500)
    private String errorMessage;

    @Column
    private Integer totalTokens;

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
