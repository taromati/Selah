package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryEdgeEntity;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryEdgeRepository;
import me.taromati.almah.memory.db.repository.MemoryEntityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class KnowledgeGraph {

    private final MemoryEntityRepository entityRepository;
    private final MemoryEdgeRepository edgeRepository;

    @Transactional("memoryTransactionManager")
    public MemoryEntityEntity addEntity(String name, String type, String description) {
        return entityRepository.findByNameAndEntityType(name, type)
                .map(existing -> {
                    if (description != null && !description.equals(existing.getDescription())) {
                        existing.setPreviousDescription(existing.getDescription());
                        existing.setDescriptionUpdatedAt(LocalDateTime.now());
                        existing.setDescription(description);
                    }
                    return entityRepository.save(existing);
                })
                .orElseGet(() -> entityRepository.save(MemoryEntityEntity.builder()
                        .name(name)
                        .entityType(type)
                        .description(description)
                        .build()));
    }

    @Transactional("memoryTransactionManager")
    public MemoryEdgeEntity addEdge(String sourceEntityId, String targetEntityId, String relationType,
                                    String sourceId, double weight,
                                    LocalDateTime validFrom, LocalDateTime validTo) {
        // bi-temporal: 동일 source-target-relation 기존 엣지 invalidate
        List<MemoryEdgeEntity> existing = edgeRepository
                .findBySourceEntityIdAndTargetEntityIdAndRelationTypeAndInvalidatedAtIsNull(
                        sourceEntityId, targetEntityId, relationType);
        LocalDateTime now = LocalDateTime.now();
        for (MemoryEdgeEntity old : existing) {
            old.setInvalidatedAt(now);
            old.setValidTo(now);
            edgeRepository.save(old);
        }

        return edgeRepository.save(MemoryEdgeEntity.builder()
                .sourceEntityId(sourceEntityId)
                .targetEntityId(targetEntityId)
                .relationType(relationType)
                .sourceId(sourceId)
                .weight(weight)
                .validFrom(validFrom)
                .validTo(validTo)
                .build());
    }

    @Transactional("memoryTransactionManager")
    public void invalidateEdge(String edgeId) {
        edgeRepository.findById(edgeId).ifPresent(edge -> {
            edge.setInvalidatedAt(LocalDateTime.now());
            edge.setValidTo(LocalDateTime.now());
            edgeRepository.save(edge);
        });
    }

    /**
     * BFS search from entity, collecting related sourceIds
     */
    public Set<String> bfsSearch(String entityId, int maxDepth) {
        Set<String> visited = new HashSet<>();
        Set<String> sourceIds = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> depths = new HashMap<>();

        queue.add(entityId);
        depths.put(entityId, 0);
        visited.add(entityId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = depths.get(current);

            if (currentDepth >= maxDepth) continue;

            List<MemoryEdgeEntity> outEdges = edgeRepository.findBySourceEntityIdAndInvalidatedAtIsNull(current);
            List<MemoryEdgeEntity> inEdges = edgeRepository.findByTargetEntityIdAndInvalidatedAtIsNull(current);

            for (MemoryEdgeEntity edge : outEdges) {
                if (edge.getSourceId() != null) sourceIds.add(edge.getSourceId());
                if (!visited.contains(edge.getTargetEntityId())) {
                    visited.add(edge.getTargetEntityId());
                    queue.add(edge.getTargetEntityId());
                    depths.put(edge.getTargetEntityId(), currentDepth + 1);
                }
            }

            for (MemoryEdgeEntity edge : inEdges) {
                if (edge.getSourceId() != null) sourceIds.add(edge.getSourceId());
                if (!visited.contains(edge.getSourceEntityId())) {
                    visited.add(edge.getSourceEntityId());
                    queue.add(edge.getSourceEntityId());
                    depths.put(edge.getSourceEntityId(), currentDepth + 1);
                }
            }
        }

        return sourceIds;
    }

    /**
     * 가중치 기반 BFS: sourceId별 누적 가중치 반환.
     */
    public Map<String, Double> bfsSearchWeighted(String entityId, int maxDepth) {
        Set<String> visited = new HashSet<>();
        Map<String, Double> sourceWeights = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> depths = new HashMap<>();

        queue.add(entityId);
        depths.put(entityId, 0);
        visited.add(entityId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = depths.get(current);

            if (currentDepth >= maxDepth) continue;

            List<MemoryEdgeEntity> outEdges = edgeRepository.findBySourceEntityIdAndInvalidatedAtIsNull(current);
            List<MemoryEdgeEntity> inEdges = edgeRepository.findByTargetEntityIdAndInvalidatedAtIsNull(current);

            for (MemoryEdgeEntity edge : outEdges) {
                double w = edge.getWeight() != null ? edge.getWeight() : 1.0;
                if (edge.getSourceId() != null) {
                    sourceWeights.merge(edge.getSourceId(), w, Double::sum);
                }
                if (!visited.contains(edge.getTargetEntityId())) {
                    visited.add(edge.getTargetEntityId());
                    queue.add(edge.getTargetEntityId());
                    depths.put(edge.getTargetEntityId(), currentDepth + 1);
                }
            }

            for (MemoryEdgeEntity edge : inEdges) {
                double w = edge.getWeight() != null ? edge.getWeight() : 1.0;
                if (edge.getSourceId() != null) {
                    sourceWeights.merge(edge.getSourceId(), w, Double::sum);
                }
                if (!visited.contains(edge.getSourceEntityId())) {
                    visited.add(edge.getSourceEntityId());
                    queue.add(edge.getSourceEntityId());
                    depths.put(edge.getSourceEntityId(), currentDepth + 1);
                }
            }
        }

        return sourceWeights;
    }

    public List<MemoryEntityEntity> findAllEntities() {
        return entityRepository.findAll();
    }

    private static final int MAX_ITERATIONS = 10;

    /**
     * Weighted Label Propagation 기반 커뮤니티 탐지.
     */
    public List<Set<String>> detectCommunities() {
        List<MemoryEntityEntity> entities = entityRepository.findAll();
        if (entities.isEmpty()) return List.of();

        Map<String, Map<String, Double>> neighbors = buildNeighborMap(entities);

        Map<String, String> labels = new HashMap<>();
        for (MemoryEntityEntity e : entities) {
            labels.put(e.getId(), e.getId());
        }

        List<String> entityIds = new ArrayList<>(labels.keySet());
        Random random = new Random(42);

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            Collections.shuffle(entityIds, random);
            int changed = 0;
            for (String id : entityIds) {
                Map<String, Double> nbrs = neighbors.getOrDefault(id, Map.of());
                if (nbrs.isEmpty()) continue;

                Map<String, Double> labelWeights = new HashMap<>();
                for (var entry : nbrs.entrySet()) {
                    String nbrLabel = labels.get(entry.getKey());
                    labelWeights.merge(nbrLabel, entry.getValue(), Double::sum);
                }

                String currentLabel = labels.get(id);
                double currentWeight = labelWeights.getOrDefault(currentLabel, 0.0);
                String bestLabel = currentLabel;
                double bestWeight = currentWeight;
                for (var entry : labelWeights.entrySet()) {
                    if (entry.getValue() > bestWeight) {
                        bestWeight = entry.getValue();
                        bestLabel = entry.getKey();
                    }
                }

                if (!bestLabel.equals(currentLabel)) {
                    labels.put(id, bestLabel);
                    changed++;
                }
            }
            if (changed == 0) break;
        }

        Map<String, Set<String>> groups = new HashMap<>();
        for (var entry : labels.entrySet()) {
            groups.computeIfAbsent(entry.getValue(), k -> new HashSet<>()).add(entry.getKey());
        }
        return groups.values().stream()
                .filter(s -> s.size() >= 2)
                .toList();
    }

    private Map<String, Map<String, Double>> buildNeighborMap(List<MemoryEntityEntity> entities) {
        Map<String, Map<String, Double>> neighbors = new HashMap<>();
        Set<String> entityIds = entities.stream()
                .map(MemoryEntityEntity::getId)
                .collect(Collectors.toSet());

        for (MemoryEntityEntity entity : entities) {
            String id = entity.getId();
            for (MemoryEdgeEntity edge : edgeRepository.findBySourceEntityIdAndInvalidatedAtIsNull(id)) {
                if (entityIds.contains(edge.getTargetEntityId())) {
                    double w = edge.getWeight() != null ? edge.getWeight() : 1.0;
                    neighbors.computeIfAbsent(id, k -> new HashMap<>())
                            .merge(edge.getTargetEntityId(), w, Double::sum);
                    neighbors.computeIfAbsent(edge.getTargetEntityId(), k -> new HashMap<>())
                            .merge(id, w, Double::sum);
                }
            }
        }
        return neighbors;
    }
}
