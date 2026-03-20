package me.taromati.almah.memory.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Entity
@Table(name = "memory_communities")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryCommunityEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String memberIds;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUIDv7.generate();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
