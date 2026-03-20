package me.taromati.almah.memory.db.repository;

import me.taromati.almah.memory.db.entity.MemoryEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryEdgeRepository extends JpaRepository<MemoryEdgeEntity, String> {
    List<MemoryEdgeEntity> findBySourceEntityIdAndInvalidatedAtIsNull(String sourceEntityId);
    List<MemoryEdgeEntity> findByTargetEntityIdAndInvalidatedAtIsNull(String targetEntityId);
    List<MemoryEdgeEntity> findBySourceId(String sourceId);
    List<MemoryEdgeEntity> findBySourceEntityIdAndTargetEntityIdAndRelationTypeAndInvalidatedAtIsNull(
            String sourceEntityId, String targetEntityId, String relationType);
    List<MemoryEdgeEntity> findBySourceIdIn(List<String> sourceIds);
}
