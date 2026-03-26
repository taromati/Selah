package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.memoryengine.model.ScoredDocument;
import me.taromati.memoryengine.search.SearchPipeline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

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
    private final SearchPipeline searchPipeline;
    private final ChunkStore chunkStore;
    private final me.taromati.memoryengine.spi.KnowledgeGraphStore graphStore;
    private final me.taromati.memoryengine.search.KnowledgeGraphEngine graphEngine;
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
     * Search memory using hybrid search (BM25 + Vector + Graph BFS + Community + RRF fusion).
     * SearchPipeline → sourceId dedup → SearchResult 변환.
     */
    public List<SearchResult> search(String query) {
        List<ScoredDocument> docs = searchPipeline.search(query);

        // sourceId dedup: 같은 sourceId 청크 중 최고 점수만 유지
        Map<String, ScoredDocument> bestBySource = new LinkedHashMap<>();
        Map<String, MemoryChunkEntity> chunkCache = new HashMap<>();

        for (ScoredDocument doc : docs) {
            var chunk = chunkRepository.findById(doc.documentId()).orElse(null);
            if (chunk == null) continue;
            chunkCache.put(doc.documentId(), chunk);
            String sourceId = chunk.getSourceId();
            bestBySource.merge(sourceId, doc,
                    (existing, candidate) -> candidate.score() > existing.score() ? candidate : existing);
        }

        return bestBySource.values().stream()
                .map(doc -> {
                    var chunk = chunkCache.get(doc.documentId());
                    return chunk != null ? new SearchResult(chunk.getId(), chunk.getContent(),
                            doc.score(), chunk.getMetadata(), chunk.getSourceId(), chunk.getChunkIndex()) : null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Explore by entity name: resolve name to entity ID, then BFS traverse.
     */
    public List<ExploreResult> exploreByName(String entityName, int hops) {
        var entities = graphStore.findAllEntities();
        var matched = entities.stream()
                .filter(e -> e.name().toLowerCase().contains(entityName.toLowerCase()))
                .findFirst();
        if (matched.isEmpty()) return List.of();

        Set<String> sourceIds = graphEngine.bfsSearchWeighted(matched.get().id(), hops).keySet();
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

    public record SearchResult(String chunkId, String content, double score,
                                String metadata, String sourceId, int chunkIndex) {}

    public record ExploreResult(String chunkId, String content, LocalDateTime createdAt) {}
}
