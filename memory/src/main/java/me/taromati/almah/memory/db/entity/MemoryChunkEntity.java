package me.taromati.almah.memory.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Entity
@Table(name = "memory_chunks", indexes = {
        @Index(name = "idx_chunk_source", columnList = "sourceId"),
        @Index(name = "idx_chunk_created", columnList = "createdAt")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryChunkEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String tokenizedContent;

    @Column(nullable = false, length = 36)
    private String sourceId;

    @Column(nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false)
    private Integer totalChunks;

    @Builder.Default
    @Column(nullable = false)
    private Boolean consolidated = false;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(columnDefinition = "BLOB")
    private byte[] embedding;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean graphProcessed = false;

    private LocalDateTime graphProcessedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUIDv7.generate();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (graphProcessed == null) graphProcessed = false;
        if (consolidated == null) consolidated = false;
    }
}
