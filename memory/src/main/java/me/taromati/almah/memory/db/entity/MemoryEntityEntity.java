package me.taromati.almah.memory.db.entity;

import jakarta.persistence.*;
import lombok.*;
import me.taromati.almah.core.util.UUIDv7;

import java.time.LocalDateTime;

@Entity
@Table(name = "memory_entities", indexes = {
        @Index(name = "idx_entity_name_type", columnList = "name, entityType")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntityEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String entityType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "BLOB")
    private byte[] embedding;

    @Column(length = 100)
    private String embeddingModel;

    @Column(columnDefinition = "TEXT")
    private String previousDescription;

    private LocalDateTime descriptionUpdatedAt;

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
