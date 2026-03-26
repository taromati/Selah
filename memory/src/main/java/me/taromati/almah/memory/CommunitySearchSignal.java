package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.memoryengine.model.FusionStrategy;
import me.taromati.memoryengine.model.Intent;
import me.taromati.memoryengine.model.GraphCommunity;
import me.taromati.memoryengine.model.ScoredDocument;
import me.taromati.memoryengine.search.KnowledgeGraphEngine;
import me.taromati.memoryengine.spi.KnowledgeGraphStore;
import me.taromati.memoryengine.spi.SearchContext;
import me.taromati.memoryengine.spi.SearchSignal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class CommunitySearchSignal implements SearchSignal {

    private final KnowledgeGraphStore graphStore;
    private final KnowledgeGraphEngine graphEngine;
    private final MemoryChunkRepository chunkRepository;

    @Override
    public String name() { return "community"; }

    @Override
    public double weight() { return 0.2; }

    @Override
    public FusionStrategy fusionStrategy() { return FusionStrategy.WEIGHTED_SUM; }

    @Override
    public double intentMultiplier(EnumSet<Intent> intents) {
        if (intents.isEmpty()) return 1.0;
        double sum = 0;
        for (Intent intent : intents) {
            sum += intent == Intent.EXPLORATORY ? 2.0 : 1.0;
        }
        return sum / intents.size();
    }

    @Override
    public List<ScoredDocument> search(String query, SearchContext context) {
        Map<String, Double> chunkScores = new HashMap<>();
        try {
            List<GraphCommunity> communities = graphStore.findAllCommunities();
            String queryLower = query.toLowerCase();

            for (GraphCommunity community : communities) {
                boolean labelMatch = community.label() != null
                        && queryLower.contains(community.label().toLowerCase());
                boolean summaryMatch = community.summary() != null
                        && queryLower.contains(community.summary().toLowerCase());

                if (labelMatch || summaryMatch) {
                    for (String memberId : community.memberNodeIds()) {
                        Map<String, Double> sourceWeights = graphEngine.bfsSearchWeighted(memberId.trim(), 1);
                        expandSourceIdsToChunkIds(sourceWeights, chunkScores);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[CommunitySearchSignal] Search failed: {}", e.getMessage());
        }

        return chunkScores.entrySet().stream()
                .map(e -> new ScoredDocument(e.getKey(), e.getValue()))
                .toList();
    }

    private void expandSourceIdsToChunkIds(Map<String, Double> sourceWeights, Map<String, Double> chunkScores) {
        if (sourceWeights.isEmpty()) return;
        List<MemoryChunkEntity> chunks = chunkRepository.findBySourceIdIn(new ArrayList<>(sourceWeights.keySet()));
        for (MemoryChunkEntity chunk : chunks) {
            double weight = sourceWeights.getOrDefault(chunk.getSourceId(), 0.0);
            chunkScores.merge(chunk.getId(), weight, Double::sum);
        }
    }
}
