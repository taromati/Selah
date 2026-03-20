package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.config.MemoryConfigProperties;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.entity.MemoryCommunityEntity;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.almah.memory.db.repository.MemoryCommunityRepository;
import me.taromati.almah.llm.embedding.EmbeddingService;
import me.taromati.almah.llm.embedding.EmbeddingUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class HybridSearch {

    private static final int RRF_K = 60;

    private final ChunkStore chunkStore;
    private final KnowledgeGraph knowledgeGraph;
    private final MemoryChunkRepository chunkRepository;
    private final MemoryCommunityRepository communityRepository;
    private final MemoryConfigProperties config;
    private final EmbeddingService embeddingService;

    /**
     * Hybrid search: BM25 + Vector + Graph BFS + Community -> RRF fusion
     */
    public List<SearchResult> search(String query) {
        int topK = config.getSearch().getTopK();
        double vectorWeight = config.getSearch().getVectorWeight();
        double keywordWeight = config.getSearch().getKeywordWeight();
        double communityWeight = config.getSearch().getCommunityWeight();
        double graphBoostFactor = 1.0;
        EnumSet<IntentClassifier.Intent> intents = IntentClassifier.classifyAll(query);

        // 의도 기반 가중치 조정
        if (Boolean.TRUE.equals(config.getSearch().getIntentAdaptive())) {
            double vectorMod = 0, keywordMod = 0, communityMod = 0, graphMod = 0;
            int count = 0;
            for (IntentClassifier.Intent intent : intents) {
                count++;
                switch (intent) {
                    case TEMPORAL -> { keywordMod += 0.5; graphMod += -0.5; }
                    case CAUSAL -> { graphMod += 1.0; vectorMod += -0.3; }
                    case EXPLORATORY -> { graphMod += 0.5; communityMod += 0.5; }
                    default -> {}
                }
            }
            if (count > 0) {
                vectorWeight *= (1.0 + vectorMod / count);
                keywordWeight *= (1.0 + keywordMod / count);
                communityWeight *= (1.0 + communityMod / count);
                graphBoostFactor = 1.0 + graphMod / count;
            }
            log.debug("[HybridSearch] Intents: {}, vectorW={}, keywordW={}, graphBoost={}, communityW={}",
                    intents, vectorWeight, keywordWeight, graphBoostFactor, communityWeight);
        }

        // 1. BM25 keyword search
        List<ChunkStore.SearchResult> keywordResults = chunkStore.searchByKeyword(query, topK * 2);

        // 2. Vector similarity search
        byte[] queryEmbedding = null;
        try {
            float[] vector = embeddingService.embed(query);
            if (vector != null) queryEmbedding = EmbeddingUtil.floatArrayToBytes(vector);
        } catch (Exception e) {
            log.debug("[HybridSearch] Query embedding failed: {}", e.getMessage());
        }
        List<ChunkStore.SearchResult> vectorResults = queryEmbedding != null
                ? chunkStore.searchByVector(queryEmbedding, topK * 2)
                : List.of();

        // 3. Graph BFS — weighted, sourceId 기반
        Map<String, Double> weightedGraphScores = new HashMap<>();
        List<MemoryEntityEntity> entities = knowledgeGraph.findAllEntities();

        // 3a. 문자열 매칭
        for (MemoryEntityEntity entity : entities) {
            if (query.toLowerCase().contains(entity.getName().toLowerCase())) {
                Map<String, Double> entityScores = knowledgeGraph.bfsSearchWeighted(entity.getId(), 2);
                // sourceId → 해당 청크들 조회하여 chunkId별 점수 부여
                expandSourceIdsToChunkIds(entityScores, weightedGraphScores);
            }
        }

        // 3b. 시맨틱 엔티티 매칭
        if (queryEmbedding != null) {
            try {
                float[] queryVector = EmbeddingUtil.bytesToFloatArray(queryEmbedding);
                double minScore = config.getSearch().getMinScore();
                entities.stream()
                        .filter(e -> e.getEmbedding() != null)
                        .map(e -> Map.entry(e, EmbeddingUtil.cosineSimilarity(
                                queryVector, EmbeddingUtil.bytesToFloatArray(e.getEmbedding()))))
                        .filter(entry -> entry.getValue() >= minScore)
                        .sorted(Map.Entry.<MemoryEntityEntity, Double>comparingByValue().reversed())
                        .limit(5)
                        .forEach(entry -> {
                            Map<String, Double> entityScores = knowledgeGraph.bfsSearchWeighted(entry.getKey().getId(), 2);
                            expandSourceIdsToChunkIds(entityScores, weightedGraphScores);
                        });
            } catch (Exception e) {
                log.debug("[HybridSearch] Entity semantic matching failed: {}", e.getMessage());
            }
        }

        // 4. Community search: community summary keyword match → boost member chunks
        Map<String, Double> communityWeightedScores = new HashMap<>();
        try {
            List<MemoryCommunityEntity> communities = communityRepository.findAll();
            String queryLower = query.toLowerCase();
            for (MemoryCommunityEntity community : communities) {
                boolean labelMatch = queryLower.contains(community.getLabel().toLowerCase());
                boolean summaryMatch = community.getSummary() != null
                        && queryLower.contains(community.getSummary().toLowerCase());
                if (labelMatch || summaryMatch) {
                    String[] memberIds = community.getMemberIds().split(",");
                    for (String memberId : memberIds) {
                        Map<String, Double> memberScores = knowledgeGraph.bfsSearchWeighted(memberId.trim(), 1);
                        expandSourceIdsToChunkIds(memberScores, communityWeightedScores);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[HybridSearch] Community search failed: {}", e.getMessage());
        }

        // 5. RRF fusion
        Map<String, Double> scores = new HashMap<>();

        for (int rank = 0; rank < vectorResults.size(); rank++) {
            String id = vectorResults.get(rank).chunkId();
            scores.merge(id, vectorWeight / (RRF_K + rank + 1), Double::sum);
        }

        for (int rank = 0; rank < keywordResults.size(); rank++) {
            String id = keywordResults.get(rank).chunkId();
            scores.merge(id, keywordWeight / (RRF_K + rank + 1), Double::sum);
        }

        for (var entry : weightedGraphScores.entrySet()) {
            double graphScore = 0.3 * graphBoostFactor * entry.getValue() / RRF_K;
            scores.merge(entry.getKey(), graphScore, Double::sum);
        }

        for (var entry : communityWeightedScores.entrySet()) {
            double commScore = communityWeight * entry.getValue() / RRF_K;
            scores.merge(entry.getKey(), commScore, Double::sum);
        }

        // 6. sourceId dedup — 같은 sourceId에서 최고 점수 청크만 유지
        Map<String, String> sourceIdByChunkId = new HashMap<>();
        if (!scores.isEmpty()) {
            List<MemoryChunkEntity> candidates = chunkRepository.findByIdIn(new ArrayList<>(scores.keySet()));
            for (MemoryChunkEntity c : candidates) {
                sourceIdByChunkId.put(c.getId(), c.getSourceId());
            }
        }

        Map<String, String> bestChunkPerSource = new HashMap<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    String sourceId = sourceIdByChunkId.get(entry.getKey());
                    if (sourceId != null) {
                        bestChunkPerSource.putIfAbsent(sourceId, entry.getKey());
                    }
                });
        Set<String> dedupedIds = new HashSet<>(bestChunkPerSource.values());

        // 7. Sort and limit
        List<String> topIds = scores.entrySet().stream()
                .filter(e -> dedupedIds.contains(e.getKey()))
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();

        if (topIds.isEmpty()) return List.of();

        // 8. Load chunk content
        Map<String, MemoryChunkEntity> chunkMap = chunkRepository.findByIdIn(topIds).stream()
                .collect(Collectors.toMap(MemoryChunkEntity::getId, c -> c));

        return topIds.stream()
                .filter(chunkMap::containsKey)
                .map(id -> new SearchResult(
                        id,
                        chunkMap.get(id).getContent(),
                        scores.get(id),
                        chunkMap.get(id).getMetadata(),
                        chunkMap.get(id).getSourceId(),
                        chunkMap.get(id).getChunkIndex()))
                .toList();
    }

    /**
     * Graph BFS가 반환하는 sourceId별 가중치를 → 해당 sourceId의 개별 chunkId별 가중치로 확장
     */
    private void expandSourceIdsToChunkIds(Map<String, Double> sourceWeights,
                                            Map<String, Double> chunkScores) {
        if (sourceWeights.isEmpty()) return;
        List<String> sourceIds = new ArrayList<>(sourceWeights.keySet());
        List<MemoryChunkEntity> chunks = chunkRepository.findBySourceIdIn(sourceIds);
        for (MemoryChunkEntity chunk : chunks) {
            double weight = sourceWeights.getOrDefault(chunk.getSourceId(), 0.0);
            chunkScores.merge(chunk.getId(), weight, Double::sum);
        }
    }

    public record SearchResult(String chunkId, String content, double score, String metadata,
                                   String sourceId, int chunkIndex) {}
}
