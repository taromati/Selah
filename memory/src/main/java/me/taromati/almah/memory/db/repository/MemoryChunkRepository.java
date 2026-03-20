package me.taromati.almah.memory.db.repository;

import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface MemoryChunkRepository extends JpaRepository<MemoryChunkEntity, String> {
    List<MemoryChunkEntity> findAllByOrderByCreatedAtDesc();

    @Query("SELECT c FROM MemoryChunkEntity c WHERE c.embedding IS NOT NULL")
    List<MemoryChunkEntity> findWithEmbedding();

    List<MemoryChunkEntity> findBySourceIdOrderByChunkIndexAsc(String sourceId);

    List<MemoryChunkEntity> findBySourceIdAndChunkIndexBetween(String sourceId, int from, int to);

    List<MemoryChunkEntity> findBySourceIdIn(List<String> sourceIds);

    List<MemoryChunkEntity> findByGraphProcessedFalse();

    List<MemoryChunkEntity> findByIdIn(List<String> ids);

    @Modifying
    @Query("DELETE FROM MemoryChunkEntity c WHERE c.id IN :ids")
    void deleteByIdIn(List<String> ids);

    List<MemoryChunkEntity> findByCreatedAtBeforeAndGraphProcessedTrue(LocalDateTime before);

    List<MemoryChunkEntity> findByCreatedAtBeforeAndGraphProcessedTrueAndConsolidatedFalse(LocalDateTime before);
}
