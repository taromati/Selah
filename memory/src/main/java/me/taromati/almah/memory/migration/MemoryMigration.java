package me.taromati.almah.memory.migration;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.almah.memory.db.repository.MemoryEntityRepository;
import me.taromati.memoryengine.embedding.EmbeddingService;
import me.taromati.memoryengine.embedding.EmbeddingUtil;
import me.taromati.memoryengine.nlp.KoreanTokenizerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryMigration implements ApplicationRunner {

    private static final String CURRENT_MODEL = "multilingual-e5-small-onnx-qint8";
    private static final int BATCH_SIZE = 16;

    private final JdbcTemplate jdbcTemplate;
    private final KoreanTokenizerService tokenizer;
    private final MemoryChunkRepository chunkRepository;
    private final MemoryEntityRepository entityRepository;
    private final ObjectProvider<EmbeddingService> embeddingServiceProvider;

    public MemoryMigration(@Qualifier("memoryJdbcTemplate") JdbcTemplate jdbcTemplate,
                           KoreanTokenizerService tokenizer,
                           MemoryChunkRepository chunkRepository,
                           MemoryEntityRepository entityRepository,
                           ObjectProvider<EmbeddingService> embeddingServiceProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenizer = tokenizer;
        this.chunkRepository = chunkRepository;
        this.entityRepository = entityRepository;
        this.embeddingServiceProvider = embeddingServiceProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        retokenizeChunks();
        createFts5Table();
        reindexFts5();
        reembedChunks();
        log.info("[MemoryMigration] Schema initialization completed");
    }

    private void retokenizeChunks() {
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
                "SELECT id, content FROM memory_chunks WHERE tokenized IS NULL");
        if (chunks.isEmpty()) return;

        for (Map<String, Object> chunk : chunks) {
            String id = (String) chunk.get("id");
            String content = (String) chunk.get("content");
            String tokenized = tokenizer.tokenize(content);
            jdbcTemplate.update(
                    "UPDATE memory_chunks SET tokenized = ? WHERE id = ?",
                    tokenized, id);
        }
        log.info("[MemoryMigration] Re-tokenized {} chunks with Nori", chunks.size());
    }

    private void createFts5Table() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS memory_chunks_fts");
            jdbcTemplate.execute("""
                    CREATE VIRTUAL TABLE memory_chunks_fts USING fts5(
                        content,
                        content='memory_chunks',
                        content_rowid='rowid',
                        tokenize='unicode61'
                    )""");
            log.info("[MemoryMigration] FTS5 table created (unicode61 tokenizer)");
        } catch (Exception e) {
            log.error("[MemoryMigration] Failed to create FTS5 table: {}", e.getMessage());
        }
    }

    private void reembedChunks() {
        EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
        if (embeddingService == null) {
            log.debug("[MemoryMigration] EmbeddingService 비활성 — 재임베딩 스킵");
            return;
        }

        // 청크 재임베딩
        List<MemoryChunkEntity> chunkTargets = chunkRepository.findByEmbeddingModelIsNullOrEmbeddingModelNot(CURRENT_MODEL);
        if (!chunkTargets.isEmpty()) {
            for (int i = 0; i < chunkTargets.size(); i += BATCH_SIZE) {
                List<MemoryChunkEntity> batch = chunkTargets.subList(i, Math.min(i + BATCH_SIZE, chunkTargets.size()));
                for (MemoryChunkEntity c : batch) {
                    float[] vec = embeddingService.embed(c.getContent());
                    if (vec != null) {
                        c.setEmbedding(EmbeddingUtil.floatArrayToBytes(vec));
                        c.setEmbeddingModel(CURRENT_MODEL);
                    }
                }
                chunkRepository.saveAll(batch);
            }
            log.info("[MemoryMigration] 청크 재임베딩 완료: {}건", chunkTargets.size());
        }

        // 엔티티 재임베딩
        List<MemoryEntityEntity> entityTargets = entityRepository.findByEmbeddingModelIsNullOrEmbeddingModelNot(CURRENT_MODEL);
        if (!entityTargets.isEmpty()) {
            for (MemoryEntityEntity e : entityTargets) {
                String text = e.getName() + (e.getDescription() != null ? " " + e.getDescription() : "");
                float[] vec = embeddingService.embed(text);
                if (vec != null) {
                    e.setEmbedding(EmbeddingUtil.floatArrayToBytes(vec));
                    e.setEmbeddingModel(CURRENT_MODEL);
                }
            }
            entityRepository.saveAll(entityTargets);
            log.info("[MemoryMigration] 엔티티 재임베딩 완료: {}건", entityTargets.size());
        }
    }

    private void reindexFts5() {
        try {
            Integer chunkCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM memory_chunks", Integer.class);
            if (chunkCount == null || chunkCount == 0) return;

            jdbcTemplate.execute("""
                    INSERT INTO memory_chunks_fts(rowid, content)
                    SELECT rowid, tokenized FROM memory_chunks
                    WHERE tokenized IS NOT NULL
                    """);
            log.info("[MemoryMigration] FTS5 reindex completed: {} chunks", chunkCount);
        } catch (Exception e) {
            log.warn("[MemoryMigration] FTS5 reindex failed: {}", e.getMessage());
        }
    }
}
