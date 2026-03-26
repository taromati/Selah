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
    private String response;  // PENDING, APPROVED, DENIED, EXPIRED, REJECTED, VERIFY_FAILED, SEND_FAILED, USER_REPLIED

    @Column(columnDefinition = "TEXT")
    private String passedActions;  // JSON: verify PASS된 ACTION 목록 [{title, description}, ...]

    @Column(length = 20)
    private String category;      // CONVERSATION, REFLECTION, EXPLORATION (Phase 1)

    @Column(length = 64)
    private String messageId;     // Discord 메시지 ID (버튼 비활성화용)

    @Column(length = 64)
    private String channelId;     // 직렬화된 ChannelRef (PLATFORM:id)

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
