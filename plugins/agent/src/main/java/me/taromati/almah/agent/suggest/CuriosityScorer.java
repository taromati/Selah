package me.taromati.almah.agent.suggest;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.embedding.EmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class CuriosityScorer {

    private final EmbeddingService embeddingService;
    private final SuggestHistory suggestHistory;
    private final AgentConfigProperties config;

    private List<float[]> recentEmbeddingsCache;

    public CuriosityScorer(@Nullable EmbeddingService embeddingService, SuggestHistory suggestHistory,
                            AgentConfigProperties config) {
        this.embeddingService = embeddingService;
        this.suggestHistory = suggestHistory;
        this.config = config;
    }

    /**
     * 생성된 제안의 Curiosity Score를 계산한다.
     * @return curiosity score (0.0~1.0). EmbeddingService 미가용 시 1.0.
     */
    public double score(String suggestionText) {
        if (embeddingService == null) return 1.0;

        try {
            float[] newEmbed = embeddingService.embed(suggestionText);
            if (newEmbed == null) return 1.0;

            var recentEmbeds = getRecentEmbeddings();
            if (recentEmbeds.isEmpty()) return 1.0;

            double maxSimilarity = 0.0;
            for (var embed : recentEmbeds) {
                if (embed != null) {
                    double sim = cosineSimilarity(newEmbed, embed);
                    maxSimilarity = Math.max(maxSimilarity, sim);
                }
            }

            return 1.0 - maxSimilarity;
        } catch (Exception e) {
            log.warn("[CuriosityScorer] 점수 계산 실패: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * Curiosity Score가 임계값 이상인지 판정.
     */
    public boolean passes(String suggestionText) {
        double threshold = config.getSuggest().getCuriosityThreshold();
        return score(suggestionText) >= threshold;
    }

    /**
     * 캐시 초기화 (generate() 1회 호출 끝에 호출).
     */
    public void clearCache() { recentEmbeddingsCache = null; }

    List<float[]> getRecentEmbeddings() {
        if (recentEmbeddingsCache != null) return recentEmbeddingsCache;
        var recent = suggestHistory.getRecent();
        if (recent.isEmpty() || embeddingService == null) {
            recentEmbeddingsCache = List.of();
            return recentEmbeddingsCache;
        }
        var texts = recent.stream()
                .map(s -> StringUtils.truncate(s.getContent(), 200))
                .limit(10)
                .toList();
        try {
            recentEmbeddingsCache = embeddingService.embedBatch(texts);
        } catch (Exception e) {
            log.warn("[CuriosityScorer] 임베딩 캐시 생성 실패: {}", e.getMessage());
            recentEmbeddingsCache = List.of();
        }
        return recentEmbeddingsCache;
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
