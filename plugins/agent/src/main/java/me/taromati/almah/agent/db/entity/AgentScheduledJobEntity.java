package me.taromati.almah.agent.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.Instant;
import java.time.LocalDateTime;

@Builder
@Data
@Entity
@Table(name = "agent_scheduled_jobs")
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentScheduledJobEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String channelId;

    @Column
    private String channelPlatform;   // "DISCORD" | "TELEGRAM" — null defaults to DISCORD

    // 스케줄
    @Column(nullable = false)
    private String scheduleType;    // "at" | "every" | "cron"

    @Column(nullable = false)
    private String scheduleValue;   // ISO timestamp | 분 | cron 6필드

    @Column
    private String timezone;        // 기본 Asia/Seoul

    // 실행
    @Column(nullable = false)
    private String executionType;   // "message" | "agent-turn"

    @Column(columnDefinition = "TEXT")
    private String payload;         // 메시지 텍스트 또는 에이전트 task

    // 상태
    @Column(nullable = false)
    private Boolean enabled;

    @Column
    private Instant nextRunAt;

    @Column
    private Instant lastRunAt;

    @Column
    private String lastRunStatus;   // "success" | "error" | null

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "max_duration_minutes")
    private Integer maxDurationMinutes;  // null = 전역 기본값 사용

    @Column(nullable = false)
    private Integer consecutiveErrors;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUIDv7.generate();
        }
        if (this.enabled == null) {
            this.enabled = true;
        }
        if (this.consecutiveErrors == null) {
            this.consecutiveErrors = 0;
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
