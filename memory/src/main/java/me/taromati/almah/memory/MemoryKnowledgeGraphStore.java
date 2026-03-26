package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.memory.db.entity.MemoryCommunityEntity;
import me.taromati.almah.memory.db.entity.MemoryEdgeEntity;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryCommunityRepository;
import me.taromati.almah.memory.db.repository.MemoryEdgeRepository;
import me.taromati.almah.memory.db.repository.MemoryEntityRepository;
import me.taromati.almah.core.util.UUIDv7;
import me.taromati.memoryengine.model.GraphCommunity;
import me.taromati.memoryengine.model.KgEdge;
import me.taromati.memoryengine.model.KgEntity;
import me.taromati.memoryengine.spi.KnowledgeGraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * memory-engine KnowledgeGraphStore SPI 구현.
 * 기존 KnowledgeGraph의 DB CRUD를 SPI로 위임.
 */
@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryKnowledgeGraphStore implements KnowledgeGraphStore {

    private final MemoryEntityRepository entityRepository;
    private final MemoryEdgeRepository edgeRepository;
    private final MemoryCommunityRepository communityRepository;

    @Override
    public String upsertEntity(String name, String type, String description) {
        var existing = entityRepository.findByNameAndEntityType(name, type);
        if (existing.isPresent()) {
            var entity = existing.get();
            entity.setPreviousDescription(entity.getDescription());
            entity.setDescription(description);
            entity.setDescriptionUpdatedAt(LocalDateTime.now());
            entityRepository.save(entity);
            return entity.getId();
        }
        var entity = MemoryEntityEntity.builder()
                .id(UUIDv7.generate())
                .name(name)
                .entityType(type)
                .description(description)
                .build();
        entityRepository.save(entity);
        return entity.getId();
    }

    @Override
    public Optional<KgEntity> findEntity(String name, String type) {
        return entityRepository.findByNameAndEntityType(name, type)
                .map(this::toKgEntity);
    }

    @Override
    public List<KgEntity> findAllEntities() {
        return entityRepository.findAll().stream()
                .map(this::toKgEntity)
                .toList();
    }

    @Override
    public void updateEntityEmbedding(String entityId, byte[] embedding) {
        entityRepository.findById(entityId).ifPresent(e -> {
            e.setEmbedding(embedding);
            entityRepository.save(e);
        });
    }

    @Override
    public void addEdge(String sourceEntityId, String targetEntityId, String relation,
                         String sourceId, double weight) {
        // bi-temporal: 기존 동일 엣지 무효화
        var existing = edgeRepository.findBySourceEntityIdAndTargetEntityIdAndRelationTypeAndInvalidatedAtIsNull(
                sourceEntityId, targetEntityId, relation);
        for (var e : existing) {
            e.setInvalidatedAt(LocalDateTime.now());
            edgeRepository.save(e);
        }

        var edge = MemoryEdgeEntity.builder()
                .id(UUIDv7.generate())
                .sourceEntityId(sourceEntityId)
                .targetEntityId(targetEntityId)
                .relationType(relation)
                .sourceId(sourceId)
                .weight(weight)
                .build();
        edgeRepository.save(edge);
    }

    @Override
    public void invalidateEdge(String sourceEntityId, String targetEntityId, String relation) {
        var existing = edgeRepository.findBySourceEntityIdAndTargetEntityIdAndRelationTypeAndInvalidatedAtIsNull(
                sourceEntityId, targetEntityId, relation);
        for (var e : existing) {
            e.setInvalidatedAt(LocalDateTime.now());
            edgeRepository.save(e);
        }
    }

    @Override
    public List<KgEdge> findOutEdges(String entityId) {
        return edgeRepository.findBySourceEntityIdAndInvalidatedAtIsNull(entityId).stream()
                .map(this::toKgEdge)
                .toList();
    }

    @Override
    public List<KgEdge> findInEdges(String entityId) {
        return edgeRepository.findByTargetEntityIdAndInvalidatedAtIsNull(entityId).stream()
                .map(this::toKgEdge)
                .toList();
    }

    @Override
    public void saveCommunities(List<GraphCommunity> communities) {
        communityRepository.deleteAll();
        var entities = communities.stream()
                .map(c -> {
                    var e = new MemoryCommunityEntity();
                    e.setId(c.id() != null ? c.id() : UUIDv7.generate());
                    e.setLabel(c.label());
                    e.setSummary(c.summary());
                    e.setMemberIds(String.join(",", c.memberNodeIds()));
                    return e;
                })
                .toList();
        communityRepository.saveAll(entities);
    }

    @Override
    public List<GraphCommunity> findAllCommunities() {
        return communityRepository.findAll().stream()
                .map(c -> new GraphCommunity(
                        c.getId(), c.getLabel(), c.getSummary(),
                        List.of(c.getMemberIds().split(","))))
                .toList();
    }

    @Override
    public List<KgEdge> findEdgesBySourceId(String sourceId) {
        return edgeRepository.findBySourceId(sourceId).stream()
                .map(this::toKgEdge)
                .toList();
    }

    @Override
    public void updateEdgeSourceId(String oldSourceId, String newSourceId) {
        var edges = edgeRepository.findBySourceId(oldSourceId);
        for (var e : edges) {
            e.setSourceId(newSourceId);
        }
        edgeRepository.saveAll(edges);
    }

    private KgEntity toKgEntity(MemoryEntityEntity e) {
        return new KgEntity(e.getId(), e.getName(), e.getEntityType(), e.getDescription(), e.getEmbedding());
    }

    private KgEdge toKgEdge(MemoryEdgeEntity e) {
        return new KgEdge(e.getId(), e.getSourceEntityId(), e.getTargetEntityId(),
                e.getRelationType(), e.getSourceId(), e.getWeight() != null ? e.getWeight() : 1.0);
    }
}
