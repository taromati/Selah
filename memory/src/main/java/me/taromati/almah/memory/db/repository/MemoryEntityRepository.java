package me.taromati.almah.memory.db.repository;

import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemoryEntityRepository extends JpaRepository<MemoryEntityEntity, String> {
    Optional<MemoryEntityEntity> findByNameAndEntityType(String name, String entityType);

    List<MemoryEntityEntity> findByEmbeddingIsNull();

    List<MemoryEntityEntity> findByEmbeddingModelIsNullOrEmbeddingModelNot(String model);
}
