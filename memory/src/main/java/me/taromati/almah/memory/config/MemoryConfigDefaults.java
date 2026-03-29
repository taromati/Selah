package me.taromati.almah.memory.config;

import me.taromati.almah.core.config.PluginConfigDefaults;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory 플러그인 기본 설정.
 * ConfigGenerator가 이 값을 수집하여 config.yml을 생성한다.
 *
 * <p>{@link #defaults()}는 {@code plugins.memory} 하위 구조를 반환한다.</p>
 * <p>{@link #memoryEngineDefaults()}는 root level {@code memory-engine} 섹션을 반환한다.</p>
 */
public class MemoryConfigDefaults implements PluginConfigDefaults {

    @Override
    public Map<String, Object> getDefaultConfig() {
        return defaults();
    }

    /** {@code plugins.memory} 하위 기본값 */
    public static Map<String, Object> defaults() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("enabled", true);
        root.put("max-chunk-age-days", 90);

        Map<String, Object> search = new LinkedHashMap<>();
        search.put("top-k", 5);
        search.put("min-score", 0.7);
        search.put("vector-weight", 1.0);
        search.put("keyword-weight", 0.5);
        search.put("community-weight", 0.2);
        search.put("intent-adaptive", true);
        root.put("search", search);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("target-tokens", 400);
        chunk.put("max-tokens", 512);
        chunk.put("min-tokens", 50);
        chunk.put("temporal-gap-minutes", 30);
        root.put("chunk", chunk);

        Map<String, Object> datasource = new LinkedHashMap<>();
        datasource.put("url", "jdbc:sqlite:./memory-data/memory.sqlite");
        datasource.put("driver-class-name", "org.sqlite.JDBC");
        root.put("datasource", datasource);

        return root;
    }

    /** root level {@code memory-engine} 섹션 기본값 */
    public static Map<String, Object> memoryEngineDefaults() {
        Map<String, Object> hnsw = new LinkedHashMap<>();
        hnsw.put("enabled", true);

        Map<String, Object> reranker = new LinkedHashMap<>();
        reranker.put("enabled", true);

        Map<String, Object> memoryEngine = new LinkedHashMap<>();
        memoryEngine.put("hnsw", hnsw);
        memoryEngine.put("reranker", reranker);

        return memoryEngine;
    }
}
