package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.almah.memory.db.repository.MemoryEntityRepository;
import me.taromati.memoryengine.embedding.EmbeddingService;
import me.taromati.memoryengine.embedding.EmbeddingUtil;
import me.taromati.memoryengine.ingestion.IngestionEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * memory-engine IngestionEngine에 위임하는 얇은 래퍼.
 * fast path + slow path를 Engine이 수행.
 * embedMissingEntities()는 알마 고유 로직으로 유지 (Engine에 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class IngestionPipeline {

    private final ObjectProvider<IngestionEngine> ingestionEngineProvider;
    private final MemoryChunkRepository chunkRepository;
    private final MemoryEntityRepository entityRepository;
    private final ObjectProvider<EmbeddingService> embeddingServiceProvider;

    /**
     * Fast path: 청킹 → 임베딩 → 저장 + FTS5 인덱싱 (동기)
     */
    public String ingestFast(String content, Map<String, String> metadata) {
        var engine = ingestionEngineProvider.getIfAvailable();
        if (engine == null) {
            log.warn("[IngestionPipeline] IngestionEngine 비활성 — ingestFast 스킵");
            return "";
        }
        var result = engine.ingestFast(content, metadata);
        return result.sourceId();
    }

    /**
     * Slow path: LLM 엔티티/엣지 추출 → 4-way 판정 → KG 갱신 (비동기)
     */
    @Async("memorySlowPathExecutor")
    public void ingestSlow(String sourceId, String content, Map<String, String> metadata) {
        var engine = ingestionEngineProvider.getIfAvailable();
        if (engine == null) {
            log.warn("[IngestionPipeline] IngestionEngine 비활성 — ingestSlow 스킵");
            return;
        }
        try {
            engine.ingestSlow(sourceId, content, metadata);
            embedMissingEntities();
        } catch (Exception e) {
            log.warn("[IngestionPipeline] ingestSlow 실패: {}", e.getMessage());
        }
    }

    /**
     * 미처리 청크 재시도 (slow path).
     */
    public void retryUnprocessedChunks() {
        var engine = ingestionEngineProvider.getIfAvailable();
        if (engine == null) return;

        var chunks = chunkRepository.findByGraphProcessedFalse();
        if (chunks.isEmpty()) return;

        // sourceId별 그룹화 → 각각 slow path 재실행
        var grouped = chunks.stream()
                .collect(java.util.stream.Collectors.groupingBy(c -> c.getSourceId()));
        for (var entry : grouped.entrySet()) {
            String content = entry.getValue().stream()
                    .sorted(java.util.Comparator.comparing(c -> c.getChunkIndex() != null ? c.getChunkIndex() : 0))
                    .map(c -> c.getContent())
                    .collect(java.util.stream.Collectors.joining("\n"));
            try {
                engine.ingestSlow(entry.getKey(), content, Map.of());
            } catch (Exception e) {
                log.warn("[IngestionPipeline] retry 실패 sourceId={}: {}", entry.getKey(), e.getMessage());
            }
        }
        embedMissingEntities();
    }

    /**
     * 엔티티 임베딩 (IngestionEngine에 없는 알마 고유 로직).
     */
    private void embedMissingEntities() {
        EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
        if (embeddingService == null) return;

        List<MemoryEntityEntity> entities = entityRepository.findByEmbeddingIsNull();
        for (MemoryEntityEntity entity : entities) {
            try {
                String text = entity.getName() + (entity.getDescription() != null ? " " + entity.getDescription() : "");
                float[] vector = embeddingService.embed(text);
                if (vector != null) {
                    entity.setEmbedding(EmbeddingUtil.floatArrayToBytes(vector));
                    entityRepository.save(entity);
                }
            } catch (Exception e) {
                log.debug("[IngestionPipeline] Entity embedding failed: {}", e.getMessage());
            }
        }
    }
}
