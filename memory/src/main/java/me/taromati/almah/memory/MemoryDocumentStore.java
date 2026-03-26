package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.memoryengine.model.ChunkData;
import me.taromati.memoryengine.model.ChunkGroup;
import me.taromati.memoryengine.model.ScoredDocument;
import me.taromati.memoryengine.model.VectorEntry;
import me.taromati.memoryengine.spi.DocumentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * memory-engine DocumentStore SPI 구현.
 * 기존 ChunkStore의 BM25/Vector 검색 + save/delete/findChunksOlderThan을 위임한다.
 */
@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryDocumentStore implements DocumentStore {

    private final ChunkStore chunkStore;
    private final MemoryChunkRepository chunkRepository;

    @Override
    public List<ScoredDocument> searchByKeyword(String tokenizedQuery, int topK) {
        return chunkStore.searchByKeyword(tokenizedQuery, topK).stream()
                .map(r -> new ScoredDocument(r.chunkId(), r.score()))
                .toList();
    }

    @Override
    public List<VectorEntry> loadVectors() {
        return chunkStore.loadAllVectors();
    }

    @Override
    public void save(String documentId, String content, String tokenized,
                     byte[] embedding, Map<String, String> metadata) {
        var chunk = new me.taromati.almah.memory.ChunkData(content, metadata != null ? metadata : Map.of(), content.length() / 2);
        chunkStore.saveAll(List.of(chunk), documentId, List.of(embedding));
    }

    @Override
    public void delete(String documentId) {
        chunkRepository.deleteByIdIn(List.of(documentId));
    }

    @Override
    public List<ChunkGroup> findChunksOlderThan(Duration maxAge) {
        LocalDateTime before = LocalDateTime.now().minus(maxAge);
        var chunks = chunkRepository.findByCreatedAtBeforeAndGraphProcessedTrueAndConsolidatedFalse(before);

        // sourceId별 그룹화
        Map<String, List<MemoryChunkEntity>> grouped = chunks.stream()
                .collect(Collectors.groupingBy(MemoryChunkEntity::getSourceId));

        return grouped.entrySet().stream()
                .map(e -> {
                    var chunkList = e.getValue();
                    List<ChunkData> chunkDataList = chunkList.stream()
                            .sorted(Comparator.comparing(c -> c.getChunkIndex() != null ? c.getChunkIndex() : 0))
                            .map(c -> new ChunkData(c.getId(), c.getContent(),
                                    c.getMetadata() != null ? Map.of("raw", c.getMetadata()) : Map.of(),
                                    c.getCreatedAt() != null ? c.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant() : null))
                            .toList();
                    return new ChunkGroup(e.getKey(), chunkDataList);
                })
                .toList();
    }
}
