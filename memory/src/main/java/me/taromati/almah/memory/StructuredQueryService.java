package me.taromati.almah.memory;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class StructuredQueryService {

    private static final Pattern UNSAFE_PATTERN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|REPLACE|ATTACH|DETACH|PRAGMA|REINDEX|VACUUM)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String SCHEMA_INFO = """
            Available tables:
            - memory_chunks(id TEXT PK, content TEXT, tokenized_content TEXT, source_id VARCHAR(36), chunk_index INTEGER, total_chunks INTEGER, consolidated BOOLEAN, metadata TEXT, embedding BLOB, created_at TEXT, graph_processed BOOLEAN, graph_processed_at TEXT)
            - memory_entities(id TEXT PK, name VARCHAR(200), entity_type VARCHAR(50), description TEXT, created_at TEXT, updated_at TEXT, previous_description TEXT, description_updated_at TEXT)
            - memory_edges(id TEXT PK, source_entity_id VARCHAR(36), target_entity_id VARCHAR(36), relation_type VARCHAR(100), source_id VARCHAR(36), weight DOUBLE, valid_from TEXT, valid_to TEXT, created_at TEXT, invalidated_at TEXT)
            - memory_communities(id TEXT PK, label VARCHAR(200), summary TEXT, member_ids TEXT, created_at TEXT, updated_at TEXT)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmClientResolver clientResolver;
    private final me.taromati.almah.memory.config.MemoryConfigProperties memoryConfig;

    public StructuredQueryService(@Qualifier("memoryJdbcTemplate") JdbcTemplate jdbcTemplate,
                                   LlmClientResolver clientResolver,
                                   me.taromati.almah.memory.config.MemoryConfigProperties memoryConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.clientResolver = clientResolver;
        this.memoryConfig = memoryConfig;
    }

    /**
     * 자연어 → SQL 변환 → 실행 → 결과 반환
     */
    public QueryResult query(String naturalLanguage) {
        // 1. LLM에 SQL 생성 요청
        String sql = generateSql(naturalLanguage);

        // 2. 보안 검증
        validateSql(sql);

        // 3. 타임아웃 설정 + 실행
        jdbcTemplate.setQueryTimeout(5);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (rows.size() > 20) {
                rows = rows.subList(0, 20);
            }
            log.info("[StructuredQueryService] Query executed: {} → {} rows", sql, rows.size());
            return new QueryResult(sql, rows);
        } catch (Exception e) {
            log.warn("[StructuredQueryService] Query execution failed: {}", e.getMessage());
            throw new RuntimeException("SQL 실행 오류: " + e.getMessage());
        }
    }

    private String generateSql(String naturalLanguage) {
        try {
            LlmClient client = clientResolver.resolve(memoryConfig.getLlmProvider());

            String prompt = """
                    다음 자연어 질문을 SQLite SQL SELECT 쿼리로 변환하세요.

                    %s

                    규칙:
                    1. SELECT 문만 생성하세요. INSERT, UPDATE, DELETE 등은 절대 사용하지 마세요.
                    2. 결과는 최대 20행으로 제한하세요 (LIMIT 20).
                    3. invalidated_at IS NULL 조건을 엣지 조회 시 포함하세요.
                    4. SQL만 응답하세요. 설명이나 마크다운 없이 순수 SQL만 출력하세요.

                    질문: %s
                    """.formatted(SCHEMA_INFO, naturalLanguage);

            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().role("system")
                            .content("SQLite SQL 변환 전문가입니다. SELECT 쿼리만 생성하세요. SQL만 응답하세요.").build(),
                    ChatMessage.builder().role("user").content(prompt).build()
            );

            ChatCompletionResponse response = client.chatCompletion(messages, 0.1);
            if (response.getChoices() == null || response.getChoices().isEmpty()) {
                throw new RuntimeException("LLM 응답이 비어있습니다.");
            }

            String sql = response.getChoices().getFirst().getMessage().getContent().trim();
            // 마크다운 코드블록 제거
            if (sql.startsWith("```")) {
                sql = sql.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            return sql;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("SQL 생성 실패: " + e.getMessage());
        }
    }

    private void validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("생성된 SQL이 비어있습니다.");
        }
        if (!sql.trim().toUpperCase().startsWith("SELECT")) {
            throw new SecurityException("SELECT 문만 허용됩니다: " + sql);
        }
        if (UNSAFE_PATTERN.matcher(sql).find()) {
            throw new SecurityException("허용되지 않은 SQL 명령: " + sql);
        }
    }

    public record QueryResult(String executedSql, List<Map<String, Object>> rows) {}
}
