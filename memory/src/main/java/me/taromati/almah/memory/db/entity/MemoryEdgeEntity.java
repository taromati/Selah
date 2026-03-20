package me.taromati.almah.memory.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Entity
@Table(name = "memory_edges", indexes = {
        @Index(name = "idx_edge_source", columnList = "sourceEntityId"),
        @Index(name = "idx_edge_target", columnList = "targetEntityId"),
        @Index(name = "idx_edge_source_id", columnList = "sourceId")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEdgeEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String sourceEntityId;

    @Column(nullable = false, length = 36)
    private String targetEntityId;

    @Column(nullable = false, length = 100)
    private String relationType;

    @Column(length = 36)
    private String sourceId;

    @Builder.Default
    private Double weight = 1.0;

    @Column(nullable = false)
    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime invalidatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUIDv7.generate();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (validFrom == null) validFrom = createdAt;
    }
}
