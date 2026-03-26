package me.taromati.almah.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.memoryengine.embedding.EmbeddingService;
import me.taromati.memoryengine.embedding.EmbeddingUtil;
import me.taromati.memoryengine.nlp.KoreanTokenizerService;
import me.taromati.almah.memory.config.MemoryConfigProperties;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.taromati.memoryengine.model.VectorEntry;

import java.util.*;
import java.util.Comparator;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class ChunkStore {

    private final MemoryChunkRepository chunkRepository;
    private final KoreanTokenizerService tokenizer;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryConfigProperties config;
    private final ObjectMapper objectMapper;

    public ChunkStore(MemoryChunkRepository chunkRepository,
                      KoreanTokenizerService tokenizer,
                      EmbeddingService embeddingService,
                      @Qualifier("memoryJdbcTemplate") JdbcTemplate jdbcTemplate,
                      MemoryConfigProperties config,
                      ObjectMapper objectMapper) {
        this.chunkRepository = chunkRepository;
        this.tokenizer = tokenizer;
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Transactional("memoryTransactionManager")
    public List<MemoryChunkEntity> saveAll(List<ChunkData> chunks, String sourceId, List<byte[]> embeddings) {
        List<MemoryChunkEntity> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            String tokenized = tokenizer.tokenize(chunk.content());
            String metadataJson = serializeMetadata(chunk.metadata());

            MemoryChunkEntity entity = MemoryChunkEntity.builder()
                    .content(chunk.content())
                    .tokenized(tokenized)
                    .sourceId(sourceId)
                    .chunkIndex(i)
                    .totalChunks(chunks.size())
                    .embedding(i < embeddings.size() ? embeddings.get(i) : null)
                    .metadata(metadataJson)
                    .build();
            entities.add(entity);
        }

        List<MemoryChunkEntity> saved = chunkRepository.saveAll(entities);

        // FTS5 indexing
        for (MemoryChunkEntity chunk : saved) {
            if (chunk.getTokenized() != null) {
                try {
                    jdbcTemplate.update(
                            "INSERT INTO memory_chunks_fts(rowid, content) " +
                                    "VALUES ((SELECT rowid FROM memory_chunks WHERE id = ?), ?)",
                            chunk.getId(), chunk.getTokenized());
                } catch (Exception e) {
                    log.warn("[ChunkStore] FTS5 indexing failed for chunk {}: {}", chunk.getId(), e.getMessage());
                }
            }
        }

        return saved;
    }

    public List<SearchResult> searchByKeyword(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();

        String tokenizedQuery = tokenizer.tokenizeForQuery(query);
        if (tokenizedQuery.isBlank()) return List.of();

        try {
            return jdbcTemplate.query(
                    """
                    SELECT c.id, -bm25(memory_chunks_fts) AS score
                    FROM memory_chunks_fts f
                    JOIN memory_chunks c ON c.rowid = f.rowid
                    WHERE f.content MATCH ?
                    ORDER BY score DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new SearchResult(
                            rs.getString("id"),
                            rs.getDouble("score")),
                    tokenizedQuery, topK);
        } catch (Exception e) {
            log.warn("[ChunkStore] BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<VectorEntry> loadAllVectors() {
        return chunkRepository.findWithEmbedding().stream()
                .filter(c -> c.getEmbedding() != null && c.getEmbedding().length > 0)
                .map(chunk -> new VectorEntry(
                        chunk.getId(),
                        EmbeddingUtil.bytesToFloatArray(chunk.getEmbedding())
                ))
                .toList();
    }

    public List<SearchResult> searchByVector(byte[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) return List.of();

        float[] queryVector = EmbeddingUtil.bytesToFloatArray(queryEmbedding);
        List<MemoryChunkEntity> chunks = chunkRepository.findWithEmbedding();

        return chunks.stream()
                .filter(c -> c.getEmbedding() != null && c.getEmbedding().length > 0)
                .map(c -> {
                    float[] chunkVector = EmbeddingUtil.bytesToFloatArray(c.getEmbedding());
                    double score = EmbeddingUtil.cosineSimilarity(queryVector, chunkVector);
                    return new SearchResult(c.getId(), score);
                })
                .filter(r -> r.score() >= config.getSearch().getMinScore())
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    @Transactional("memoryTransactionManager")
    public void deleteChunks(List<String> chunkIds) {
        if (chunkIds.isEmpty()) return;

        for (String id : chunkIds) {
            try {
                jdbcTemplate.update(
                        "DELETE FROM memory_chunks_fts WHERE rowid = (SELECT rowid FROM memory_chunks WHERE id = ?)",
                        id);
            } catch (Exception e) {
                log.warn("[ChunkStore] FTS5 cleanup failed for chunk {}: {}", id, e.getMessage());
            }
        }
        chunkRepository.deleteByIdIn(chunkIds);
    }

    @Transactional("memoryTransactionManager")
    public void deleteByMetadata(String key, String value) {
        List<MemoryChunkEntity> allChunks = chunkRepository.findAll();
        List<String> toDelete = new ArrayList<>();

        for (MemoryChunkEntity chunk : allChunks) {
            if (chunk.getMetadata() != null) {
                try {
                    var meta = objectMapper.readTree(chunk.getMetadata());
                    if (meta.has(key) && value.equals(meta.get(key).asText())) {
                        toDelete.add(chunk.getId());
                    }
                } catch (Exception ignored) {}
            }
        }

        if (!toDelete.isEmpty()) {
            deleteChunks(toDelete);
        }
    }

    @Transactional("memoryTransactionManager")
    public void deleteAll() {
        try {
            jdbcTemplate.update("DELETE FROM memory_chunks_fts");
        } catch (Exception e) {
            log.warn("[ChunkStore] FTS5 cleanup failed: {}", e.getMessage());
        }
        chunkRepository.deleteAllInBatch();
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("[ChunkStore] Failed to serialize metadata: {}", e.getMessage());
            return null;
        }
    }

    public record SearchResult(String chunkId, double score) {}
}
