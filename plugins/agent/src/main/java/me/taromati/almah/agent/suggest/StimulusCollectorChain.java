package me.taromati.almah.agent.suggest;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class StimulusCollectorChain {

    private final ConversationCollector conversationCollector;
    private final ReflectionCollector reflectionCollector;
    private final ExplorationCollector explorationCollector;
    private final AgentConfigProperties config;
    private final Random random;

    public StimulusCollectorChain(ConversationCollector conversationCollector,
                                   ReflectionCollector reflectionCollector,
                                   ExplorationCollector explorationCollector,
                                   AgentConfigProperties config) {
        this.conversationCollector = conversationCollector;
        this.reflectionCollector = reflectionCollector;
        this.explorationCollector = explorationCollector;
        this.config = config;
        this.random = new Random();
    }

    /**
     * ε-greedy 카테고리 선택 후 자극 수집.
     * 탐색 실패 또는 빈 결과 시 대화 → 성찰 순으로 폴백.
     */
    public StimulusResult collect() {
        double explorationRate = config.getSuggest().getExplorationRate();

        if (random.nextDouble() < explorationRate) {
            var result = safeCollect(explorationCollector);
            if (!result.isEmpty()) return result;
            log.info("[StimulusChain] 탐색 자극 비어있음, 대화로 폴백");
        }

        var conv = safeCollect(conversationCollector);
        if (!conv.isEmpty()) return conv;

        var refl = safeCollect(reflectionCollector);
        if (!refl.isEmpty()) return refl;

        return new StimulusResult(StimulusCategory.REFLECTION, List.of(), "");
    }

    private StimulusResult safeCollect(StimulusCollector collector) {
        try {
            return collector.collect();
        } catch (Exception e) {
            log.warn("[StimulusChain] {} 수집 실패: {}",
                    collector.category(), e.getMessage());
            return new StimulusResult(collector.category(), List.of(), "");
        }
    }
}
