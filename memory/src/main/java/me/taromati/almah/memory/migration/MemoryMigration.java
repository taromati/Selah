package me.taromati.almah.memory.migration;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.KoreanTokenizerService;
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

    private final JdbcTemplate jdbcTemplate;
    private final KoreanTokenizerService tokenizer;

    public MemoryMigration(@Qualifier("memoryJdbcTemplate") JdbcTemplate jdbcTemplate,
                           KoreanTokenizerService tokenizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenizer = tokenizer;
    }

    @Override
    public void run(ApplicationArguments args) {
        retokenizeChunks();
        createFts5Table();
        reindexFts5();
        log.info("[MemoryMigration] Schema initialization completed");
    }

    private void retokenizeChunks() {
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
                "SELECT id, content FROM memory_chunks WHERE tokenized_content IS NULL");
        if (chunks.isEmpty()) return;

        for (Map<String, Object> chunk : chunks) {
            String id = (String) chunk.get("id");
            String content = (String) chunk.get("content");
            String tokenized = tokenizer.tokenize(content);
            jdbcTemplate.update(
                    "UPDATE memory_chunks SET tokenized_content = ? WHERE id = ?",
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

    private void reindexFts5() {
        try {
            Integer chunkCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM memory_chunks", Integer.class);
            if (chunkCount == null || chunkCount == 0) return;

            jdbcTemplate.execute("""
                    INSERT INTO memory_chunks_fts(rowid, content)
                    SELECT rowid, tokenized_content FROM memory_chunks
                    WHERE tokenized_content IS NOT NULL
                    """);
            log.info("[MemoryMigration] FTS5 reindex completed: {} chunks", chunkCount);
        } catch (Exception e) {
            log.warn("[MemoryMigration] FTS5 reindex failed: {}", e.getMessage());
        }
    }
}
