package me.taromati.almah.memory.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "plugins.memory")
public class MemoryConfigProperties {
    private Boolean enabled = false;
    /** LLM 프로바이더 (엔티티 추출, 통합 요약, 구조화 쿼리). 필수. */
    private String llmProvider;
    private int maxChunkAgeDays = 90;
    private SearchConfig search = new SearchConfig();
    private ChunkConfig chunk = new ChunkConfig();

    @PostConstruct
    void validate() {
        if (Boolean.TRUE.equals(enabled) && (llmProvider == null || llmProvider.isBlank())) {
            throw new IllegalStateException(
                    "plugins.memory.llm-provider 가 설정되지 않았습니다. config.yml에 명시하세요.");
        }
    }

    @Getter
    @Setter
    public static class SearchConfig {
        private Integer topK = 5;
        private Double minScore = 0.7;
        private Double vectorWeight = 1.0;
        private Double keywordWeight = 0.5;
        private Double communityWeight = 0.2;
        private Boolean intentAdaptive = true;
    }

    @Getter
    @Setter
    public static class ChunkConfig {
        private int targetTokens = 400;
        private int maxTokens = 512;
        private int minTokens = 50;
        private int temporalGapMinutes = 30;
    }
}
