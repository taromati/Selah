package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Memory module public API.
 * All access from external modules goes through this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryService {

    private final IngestionPipeline ingestionPipeline;
    private final HybridSearch hybridSearch;
    private final ChunkStore chunkStore;
    private final KnowledgeGraph knowledgeGraph;
    private final MemoryChunkRepository chunkRepository;

    /**
     * Ingest content into memory.
     * Fast path (synchronous): chunking + embedding + FTS5
     * Slow path (async): LLM entity extraction + KG update
     */
    public void ingest(String content, Map<String, String> metadata) {
        String sourceId = ingestionPipeline.ingestFast(content, metadata);
        ingestionPipeline.ingestSlow(sourceId, content, metadata);
        log.debug("[MemoryService] Ingested content, sourceId={}", sourceId);
    }

    /**
     * Search memory using hybrid search (BM25 + Vector + Graph BFS + RRF fusion)
     */
    public List<HybridSearch.SearchResult> search(String query) {
        return hybridSearch.search(query);
    }

    /**
     * Explore by entity name: resolve name to entity ID, then BFS traverse.
     */
    public List<ExploreResult> exploreByName(String entityName, int hops) {
        List<MemoryEntityEntity> entities = knowledgeGraph.findAllEntities();
        var matched = entities.stream()
                .filter(e -> e.getName().toLowerCase().contains(entityName.toLowerCase()))
                .findFirst();
        if (matched.isEmpty()) return List.of();

        Set<String> sourceIds = knowledgeGraph.bfsSearch(matched.get().getId(), hops);
        if (sourceIds.isEmpty()) return List.of();

        List<MemoryChunkEntity> chunks = chunkRepository.findBySourceIdIn(sourceIds.stream().toList());
        return chunks.stream()
                .sorted(Comparator.comparing(MemoryChunkEntity::getCreatedAt).reversed())
                .map(c -> new ExploreResult(c.getId(), c.getContent(), c.getCreatedAt()))
                .toList();
    }

    /**
     * Delete chunks matching metadata field
     */
    public void deleteByMetadata(String key, String value) {
        chunkStore.deleteByMetadata(key, value);
        log.debug("[MemoryService] Deleted chunks where {}={}", key, value);
    }

    /**
     * Delete all memory data
     */
    public void deleteAll() {
        chunkStore.deleteAll();
        log.debug("[MemoryService] Deleted all chunks");
    }

    /**
     * Retry unprocessed chunks (graph processing).
     */
    public void retryUnprocessedChunks() {
        ingestionPipeline.retryUnprocessedChunks();
    }

    public record ExploreResult(String chunkId, String content, LocalDateTime createdAt) {}
}
