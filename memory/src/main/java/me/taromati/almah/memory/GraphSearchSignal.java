package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.memoryengine.embedding.EmbeddingUtil;
import me.taromati.memoryengine.model.FusionStrategy;
import me.taromati.memoryengine.model.Intent;
import me.taromati.memoryengine.model.KgEntity;
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
public class GraphSearchSignal implements SearchSignal {

    private final KnowledgeGraphEngine graphEngine;
    private final KnowledgeGraphStore graphStore;
    private final MemoryChunkRepository chunkRepository;

    @Override
    public String name() { return "graph"; }

    @Override
    public double weight() { return 0.3; }

    @Override
    public FusionStrategy fusionStrategy() { return FusionStrategy.WEIGHTED_SUM; }

    @Override
    public double intentMultiplier(EnumSet<Intent> intents) {
        if (intents.isEmpty()) return 1.0;
        double sum = 0;
        for (Intent intent : intents) {
            sum += switch (intent) {
                case CAUSAL -> 2.0;
                case EXPLORATORY -> 1.5;
                case TEMPORAL -> 0.5;
                case FACTUAL, EMOTIONAL -> 1.0;
            };
        }
        return sum / intents.size();
    }

    @Override
    public List<ScoredDocument> search(String query, SearchContext context) {
        Map<String, Double> chunkScores = new HashMap<>();
        List<KgEntity> entities = graphStore.findAllEntities();

        for (KgEntity entity : entities) {
            if (query.toLowerCase().contains(entity.name().toLowerCase())) {
                Map<String, Double> sourceWeights = graphEngine.bfsSearchWeighted(entity.id(), 2);
                expandSourceIdsToChunkIds(sourceWeights, chunkScores);
            }
        }

        float[] queryVector = context.queryVector();
        if (queryVector != null) {
            try {
                entities.stream()
                        .filter(e -> e.embedding() != null)
                        .map(e -> Map.entry(e, EmbeddingUtil.cosineSimilarity(
                                queryVector, EmbeddingUtil.bytesToFloatArray(e.embedding()))))
                        .filter(entry -> entry.getValue() >= 0.7)
                        .sorted(Map.Entry.<KgEntity, Double>comparingByValue().reversed())
                        .limit(5)
                        .forEach(entry -> {
                            Map<String, Double> sourceWeights = graphEngine.bfsSearchWeighted(entry.getKey().id(), 2);
                            expandSourceIdsToChunkIds(sourceWeights, chunkScores);
                        });
            } catch (Exception e) {
                log.debug("[GraphSearchSignal] Semantic matching failed: {}", e.getMessage());
            }
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
