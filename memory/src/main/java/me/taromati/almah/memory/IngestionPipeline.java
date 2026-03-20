package me.taromati.almah.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.memory.config.MemoryConfigProperties;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.entity.MemoryCommunityEntity;
import me.taromati.almah.memory.db.entity.MemoryEdgeEntity;
import me.taromati.almah.memory.db.entity.MemoryEntityEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.almah.memory.db.repository.MemoryCommunityRepository;
import me.taromati.almah.memory.db.repository.MemoryEdgeRepository;
import me.taromati.almah.memory.db.repository.MemoryEntityRepository;
import me.taromati.almah.llm.embedding.EmbeddingService;
import me.taromati.almah.llm.embedding.EmbeddingUtil;
import me.taromati.almah.core.util.UUIDv7;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class IngestionPipeline {

    private final ChunkStore chunkStore;
    private final ChunkingService chunkingService;
    private final KnowledgeGraph knowledgeGraph;
    private final MemoryEdgeRepository edgeRepository;
    private final MemoryChunkRepository chunkRepository;
    private final MemoryEntityRepository entityRepository;
    private final MemoryCommunityRepository communityRepository;
    private final LlmClientResolver clientResolver;
    private final EmbeddingService embeddingService;
    private final MemoryConfigProperties memoryConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestionPipeline(ChunkStore chunkStore,
                             ChunkingService chunkingService,
                             KnowledgeGraph knowledgeGraph,
                             MemoryEdgeRepository edgeRepository,
                             MemoryChunkRepository chunkRepository,
                             MemoryEntityRepository entityRepository,
                             MemoryCommunityRepository communityRepository,
                             LlmClientResolver clientResolver,
                             EmbeddingService embeddingService,
                             MemoryConfigProperties memoryConfig) {
        this.chunkStore = chunkStore;
        this.chunkingService = chunkingService;
        this.knowledgeGraph = knowledgeGraph;
        this.edgeRepository = edgeRepository;
        this.chunkRepository = chunkRepository;
        this.entityRepository = entityRepository;
        this.communityRepository = communityRepository;
        this.clientResolver = clientResolver;
        this.embeddingService = embeddingService;
        this.memoryConfig = memoryConfig;
    }

    // ── Inner records for extract results ──

    record ExtractResult(List<EntityCandidate> entities, List<EdgeCandidate> edges) {}
    record EntityCandidate(String name, String type, String description) {}
    record EdgeCandidate(String source, String target, String relation, String description, double weight,
                         String validFrom, String validTo) {}

    /**
     * Fast path: 청킹 → 임베딩 → 저장 + FTS5 인덱싱 (동기, LLM 없음)
     * @return sourceId (청크 그룹 ID)
     */
    public String ingestFast(String content, Map<String, String> metadata) {
        String sourceId = UUIDv7.generate();
        String metadataJson = serializeMetadata(metadata);

        // 청킹
        List<ChunkData> chunks = chunkingService.chunk(content, metadataJson);
        if (chunks.isEmpty()) return sourceId;

        // 임베딩 생성 (TX 외부)
        List<byte[]> embeddings = new ArrayList<>();
        for (ChunkData chunk : chunks) {
            try {
                float[] vector = embeddingService.embed(chunk.content());
                embeddings.add(vector != null ? EmbeddingUtil.floatArrayToBytes(vector) : null);
            } catch (Exception e) {
                log.debug("[IngestionPipeline] Embedding failed for chunk: {}", e.getMessage());
                embeddings.add(null);
            }
        }

        // 저장 (TX 내부 + FTS5)
        chunkStore.saveAll(chunks, sourceId, embeddings);
        return sourceId;
    }

    /**
     * Slow path: LLM 기반 엔티티/엣지 추출 + Judge 4-way + KG 갱신 (비동기)
     * 원본 content를 기준으로 추출 (청크 단위가 아닌 전체 맥락)
     */
    @Async("memorySlowPathExecutor")
    public void ingestSlow(String sourceId, String content, Map<String, String> metadata) {
        try {
            LlmClient client = clientResolver.resolve(memoryConfig.getLlmProvider());
            ExtractResult extractResult = extractEntitiesAndEdges(client, content, metadata);
            if (extractResult != null) {
                judgeAndApply(client, content, sourceId, extractResult);
                markGraphProcessed(sourceId);
            }
            embedMissingEntities();
            updateCommunities(client);
        } catch (Exception e) {
            log.warn("[IngestionPipeline] Slow path failed for sourceId {}: {}", sourceId, e.getMessage());
        }
    }

    /**
     * 미처리 청크 재시도: graphProcessed=false인 청크 그룹의 sourceId를 조회하여 각각 slow path 재실행
     */
    public void retryUnprocessedChunks() {
        List<MemoryChunkEntity> unprocessed = chunkRepository.findByGraphProcessedFalse();
        if (unprocessed.isEmpty()) {
            log.debug("[IngestionPipeline] No unprocessed chunks");
            return;
        }

        // sourceId 단위로 그룹화 (같은 그룹은 한 번만 처리)
        Map<String, List<MemoryChunkEntity>> bySourceId = unprocessed.stream()
                .collect(Collectors.groupingBy(MemoryChunkEntity::getSourceId));

        log.info("[IngestionPipeline] Retrying {} unprocessed chunk groups", bySourceId.size());
        LlmClient client = clientResolver.resolve(memoryConfig.getLlmProvider());

        for (var entry : bySourceId.entrySet()) {
            String sourceId = entry.getKey();
            try {
                // 원본 content 재구성: chunkIndex 순 결합
                String content = entry.getValue().stream()
                        .sorted(Comparator.comparingInt(MemoryChunkEntity::getChunkIndex))
                        .map(MemoryChunkEntity::getContent)
                        .collect(Collectors.joining("\n"));

                Map<String, String> metadata = deserializeMetadata(entry.getValue().getFirst().getMetadata());

                ExtractResult extractResult = extractEntitiesAndEdges(client, content, metadata);
                if (extractResult != null) {
                    judgeAndApply(client, content, sourceId, extractResult);
                    markGraphProcessed(sourceId);
                }
                embedMissingEntities();
            } catch (Exception e) {
                log.warn("[IngestionPipeline] Retry failed for sourceId {}: {}", sourceId, e.getMessage());
            }
        }
        // 전체 재시도 완료 후 커뮤니티 갱신
        updateCommunities(client);
    }

    /**
     * 커뮤니티 탐지 + LLM 요약 갱신
     */
    void updateCommunities(LlmClient client) {
        try {
            List<Set<String>> communities = knowledgeGraph.detectCommunities();
            if (communities.isEmpty()) return;

            List<MemoryCommunityEntity> existing = communityRepository.findAll();
            Map<String, MemoryCommunityEntity> existingByMembers = new HashMap<>();
            for (MemoryCommunityEntity c : existing) {
                existingByMembers.put(c.getMemberIds(), c);
            }

            List<MemoryEntityEntity> allEntities = knowledgeGraph.findAllEntities();
            Map<String, MemoryEntityEntity> entityById = allEntities.stream()
                    .collect(Collectors.toMap(MemoryEntityEntity::getId, e -> e, (a, b) -> a));

            Set<String> processedMemberKeys = new HashSet<>();

            for (Set<String> community : communities) {
                List<String> sortedIds = community.stream().sorted().toList();
                String memberKey = String.join(",", sortedIds);
                processedMemberKeys.add(memberKey);

                if (existingByMembers.containsKey(memberKey)) continue;

                StringBuilder memberInfo = new StringBuilder();
                for (String id : sortedIds) {
                    MemoryEntityEntity entity = entityById.get(id);
                    if (entity != null) {
                        memberInfo.append("- ").append(entity.getName())
                                .append(" (").append(entity.getEntityType()).append(")")
                                .append(entity.getDescription() != null ? ": " + entity.getDescription() : "")
                                .append("\n");
                    }
                }

                if (memberInfo.isEmpty()) continue;

                try {
                    String prompt = """
                            다음 엔티티들이 하나의 커뮤니티를 형성합니다. 이 커뮤니티의 레이블(10자 이내)과 요약(100자 이내)을 JSON으로 생성하세요.

                            엔티티:
                            %s

                            JSON 형식: {"label": "레이블", "summary": "요약"}
                            """.formatted(memberInfo.toString().trim());

                    List<ChatMessage> messages = List.of(
                            ChatMessage.builder().role("system")
                                    .content("커뮤니티 요약 전문가입니다. JSON만 응답하세요.").build(),
                            ChatMessage.builder().role("user").content(prompt).build()
                    );

                    ChatCompletionResponse response = client.chatCompletion(messages, 0.1);
                    if (response.getChoices() == null || response.getChoices().isEmpty()) continue;

                    String jsonStr = extractJson(response.getChoices().getFirst().getMessage().getContent(), "{", "}");
                    if (jsonStr == null) continue;

                    JsonNode root = objectMapper.readTree(jsonStr);
                    String label = root.has("label") ? root.get("label").asText() : "커뮤니티";
                    String summary = root.has("summary") ? root.get("summary").asText() : "";

                    MemoryCommunityEntity communityEntity = MemoryCommunityEntity.builder()
                            .label(label)
                            .summary(summary)
                            .memberIds(memberKey)
                            .build();
                    communityRepository.save(communityEntity);

                    log.debug("[IngestionPipeline] Created community '{}' with {} members", label, sortedIds.size());
                } catch (Exception e) {
                    log.warn("[IngestionPipeline] Community summary generation failed: {}", e.getMessage());
                }
            }

            // 더 이상 존재하지 않는 커뮤니티 삭제
            for (MemoryCommunityEntity old : existing) {
                if (!processedMemberKeys.contains(old.getMemberIds())) {
                    communityRepository.delete(old);
                    log.debug("[IngestionPipeline] Removed stale community '{}'", old.getLabel());
                }
            }
        } catch (Exception e) {
            log.warn("[IngestionPipeline] Community update failed: {}", e.getMessage());
        }
    }

    @Transactional("memoryTransactionManager")
    public void markGraphProcessed(String sourceId) {
        List<MemoryChunkEntity> chunks = chunkRepository.findBySourceIdOrderByChunkIndexAsc(sourceId);
        LocalDateTime now = LocalDateTime.now();
        for (MemoryChunkEntity chunk : chunks) {
            chunk.setGraphProcessed(true);
            chunk.setGraphProcessedAt(now);
        }
        chunkRepository.saveAll(chunks);
    }

    @Transactional("memoryTransactionManager")
    void embedMissingEntities() {
        try {
            List<MemoryEntityEntity> missing = entityRepository.findByEmbeddingIsNull();
            if (missing.isEmpty()) return;

            log.debug("[IngestionPipeline] Embedding {} entities", missing.size());

            int batchSize = 10;
            for (int i = 0; i < missing.size(); i += batchSize) {
                List<MemoryEntityEntity> batch = missing.subList(i, Math.min(i + batchSize, missing.size()));
                List<String> texts = batch.stream()
                        .map(e -> e.getName() + (e.getDescription() != null ? " " + e.getDescription() : ""))
                        .toList();

                List<float[]> embeddings = embeddingService.embedBatch(texts);
                for (int j = 0; j < Math.min(batch.size(), embeddings.size()); j++) {
                    try {
                        batch.get(j).setEmbedding(EmbeddingUtil.floatArrayToBytes(embeddings.get(j)));
                        entityRepository.save(batch.get(j));
                    } catch (Exception e) {
                        log.debug("[IngestionPipeline] Entity embedding save failed: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[IngestionPipeline] Entity embedding failed: {}", e.getMessage());
        }
    }

    // ── Extract: 후보만 추출, KG 저장 안 함 ──

    private ExtractResult extractEntitiesAndEdges(LlmClient client, String content, Map<String, String> metadata) {
        String speaker = "알 수 없음";
        if (metadata != null && metadata.containsKey("role")) {
            String role = metadata.get("role");
            speaker = "user".equals(role) ? "사용자" : "assistant".equals(role) ? "AI" : "알 수 없음";
        }

        String extractPrompt = """
                다음 텍스트는 %s가 한 말입니다. 엔티티와 관계 추출 시 "나", "저" 등의 1인칭은 "%s"로 치환하세요.

                다음 텍스트에서 엔티티(사람, 장소, 개념, 이벤트)와 관계를 추출하세요.
                JSON 형식으로 응답하세요:
                {"entities": [{"name": "이름", "type": "person|place|concept|event", "description": "설명"}],
                 "edges": [{"source": "소스이름", "target": "대상이름", "relation": "관계유형", "description": "관계 설명", "weight": 1.0, "validFrom": "유효 시작 시점(있으면)", "validTo": "유효 종료 시점(있으면)"}]}

                관계 유형은 가능하면 다음 중에서 선택하세요:
                친구, 가족, 동료, 관심사, 소유, 위치, 참여, 원인, 결과, 선호, 비선호, 습관, 경험
                필요한 경우 새로운 유형을 사용할 수 있지만, 위 목록으로 대체 가능하면 우선 사용하세요.

                텍스트:
                """.formatted(speaker, speaker) + content;

        try {
            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().role("system")
                            .content("엔티티와 관계를 추출하는 전문가입니다. JSON만 응답하세요.").build(),
                    ChatMessage.builder().role("user").content(extractPrompt).build()
            );

            ChatCompletionResponse response = client.chatCompletion(messages, 0.1);
            if (response.getChoices() == null || response.getChoices().isEmpty()) return null;

            String responseText = response.getChoices().getFirst().getMessage().getContent();
            String jsonStr = extractJson(responseText, "{", "}");
            if (jsonStr == null) return null;

            JsonNode root = objectMapper.readTree(jsonStr);

            List<EntityCandidate> entities = new ArrayList<>();
            JsonNode entitiesNode = root.get("entities");
            if (entitiesNode != null && entitiesNode.isArray()) {
                for (JsonNode node : entitiesNode) {
                    String name = node.has("name") ? node.get("name").asText() : null;
                    String type = node.has("type") ? node.get("type").asText() : "concept";
                    String desc = node.has("description") ? node.get("description").asText() : null;
                    if (name != null && !name.isBlank()) {
                        entities.add(new EntityCandidate(name, type, desc));
                    }
                }
            }

            List<EdgeCandidate> edges = new ArrayList<>();
            JsonNode edgesNode = root.get("edges");
            if (edgesNode != null && edgesNode.isArray()) {
                for (JsonNode node : edgesNode) {
                    String source = node.has("source") ? node.get("source").asText() : null;
                    String target = node.has("target") ? node.get("target").asText() : null;
                    String relation = node.has("relation") ? node.get("relation").asText() : "related_to";
                    String desc = node.has("description") ? node.get("description").asText() : null;
                    double weight = node.has("weight") ? node.get("weight").asDouble(1.0) : 1.0;
                    String validFrom = node.has("validFrom") && !node.get("validFrom").isNull() ? node.get("validFrom").asText() : null;
                    String validTo = node.has("validTo") && !node.get("validTo").isNull() ? node.get("validTo").asText() : null;
                    if (source != null && target != null) {
                        edges.add(new EdgeCandidate(source, target, relation, desc, weight, validFrom, validTo));
                    }
                }
            }

            log.debug("[IngestionPipeline] Extracted {} entities, {} edges for sourceId",
                    entities.size(), edges.size());
            return new ExtractResult(entities, edges);
        } catch (Exception e) {
            log.warn("[IngestionPipeline] Entity extraction failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Judge 4-way: ADD/UPDATE/DELETE/NOOP ──

    private void judgeAndApply(LlmClient client, String content, String sourceId, ExtractResult extractResult) {
        judgeEntities(client, content, extractResult.entities());
        judgeEdges(client, content, sourceId, extractResult.edges());
    }

    private void judgeEntities(LlmClient client, String content, List<EntityCandidate> candidates) {
        if (candidates.isEmpty()) return;

        List<MemoryEntityEntity> existingEntities = knowledgeGraph.findAllEntities();
        Map<String, MemoryEntityEntity> existingByName = existingEntities.stream()
                .collect(Collectors.toMap(e -> e.getName().toLowerCase(), e -> e, (a, b) -> a));

        List<EntityCandidate> needsJudge = new ArrayList<>();
        List<String> existingSummaries = new ArrayList<>();

        for (EntityCandidate candidate : candidates) {
            MemoryEntityEntity existing = existingByName.get(candidate.name().toLowerCase());
            if (existing == null) {
                knowledgeGraph.addEntity(candidate.name(), candidate.type(), candidate.description());
            } else {
                needsJudge.add(candidate);
                existingSummaries.add("[%s] type=%s, desc=%s".formatted(
                        existing.getName(), existing.getEntityType(),
                        existing.getDescription() != null ? existing.getDescription() : "(없음)"));
            }
        }

        if (needsJudge.isEmpty()) return;

        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("새 에피소드 내용:\n").append(content).append("\n\n");
            prompt.append("다음 새 엔티티 후보와 기존 엔티티를 비교하여 판정하세요.\n");
            prompt.append("각 후보에 대해 ADD(새로 추가), UPDATE(기존 설명 보강), NOOP(변경 불필요) 중 하나를 판정하세요.\n\n");

            for (int i = 0; i < needsJudge.size(); i++) {
                EntityCandidate c = needsJudge.get(i);
                prompt.append(i).append(". 후보: [").append(c.name()).append("] type=").append(c.type())
                        .append(", desc=").append(c.description() != null ? c.description() : "(없음)").append("\n");
                prompt.append("   기존: ").append(existingSummaries.get(i)).append("\n");
            }

            prompt.append("\nJSON 배열로 응답하세요. 예시: [{\"index\": 0, \"action\": \"UPDATE\", \"description\": \"보강된 설명\"}]");

            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().role("system")
                            .content("지식 그래프 엔티티 판정 전문가입니다. JSON만 응답하세요.").build(),
                    ChatMessage.builder().role("user").content(prompt.toString()).build()
            );

            ChatCompletionResponse response = client.chatCompletion(messages, 0.1);
            if (response.getChoices() == null || response.getChoices().isEmpty()) return;

            String jsonStr = extractJson(response.getChoices().getFirst().getMessage().getContent(), "[", "]");
            if (jsonStr == null) return;

            JsonNode decisions = objectMapper.readTree(jsonStr);
            if (!decisions.isArray()) return;

            for (JsonNode decision : decisions) {
                int index = decision.has("index") ? decision.get("index").asInt(-1) : -1;
                String action = decision.has("action") ? decision.get("action").asText() : "NOOP";
                if (index < 0 || index >= needsJudge.size()) continue;

                EntityCandidate candidate = needsJudge.get(index);
                switch (action.toUpperCase()) {
                    case "ADD" -> knowledgeGraph.addEntity(candidate.name(), candidate.type(), candidate.description());
                    case "UPDATE" -> {
                        String desc = decision.has("description") ? decision.get("description").asText() : candidate.description();
                        knowledgeGraph.addEntity(candidate.name(), candidate.type(), desc);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[IngestionPipeline] Entity judge failed: {}", e.getMessage());
            for (EntityCandidate c : needsJudge) {
                knowledgeGraph.addEntity(c.name(), c.type(), c.description());
            }
        }
    }

    private void judgeEdges(LlmClient client, String content, String sourceId, List<EdgeCandidate> candidates) {
        if (candidates.isEmpty()) return;

        List<MemoryEntityEntity> existingEntities = knowledgeGraph.findAllEntities();
        Map<String, MemoryEntityEntity> entityByName = existingEntities.stream()
                .collect(Collectors.toMap(e -> e.getName().toLowerCase(), e -> e, (a, b) -> a));
        Map<String, String> entityNameMap = existingEntities.stream()
                .collect(Collectors.toMap(MemoryEntityEntity::getId, MemoryEntityEntity::getName, (a, b) -> a));

        Set<String> entityIds = existingEntities.stream()
                .map(MemoryEntityEntity::getId)
                .collect(Collectors.toSet());

        List<MemoryEdgeEntity> existingEdges = entityIds.stream()
                .flatMap(id -> Stream.concat(
                        edgeRepository.findBySourceEntityIdAndInvalidatedAtIsNull(id).stream(),
                        edgeRepository.findByTargetEntityIdAndInvalidatedAtIsNull(id).stream()))
                .filter(edge -> !sourceId.equals(edge.getSourceId()))
                .distinct()
                .toList();

        StringBuilder existingSummary = new StringBuilder();
        List<MemoryEdgeEntity> indexedExistingEdges = new ArrayList<>();
        for (int i = 0; i < existingEdges.size() && i < 30; i++) {
            MemoryEdgeEntity edge = existingEdges.get(i);
            String srcName = entityNameMap.getOrDefault(edge.getSourceEntityId(), edge.getSourceEntityId());
            String tgtName = entityNameMap.getOrDefault(edge.getTargetEntityId(), edge.getTargetEntityId());
            existingSummary.append("E").append(i).append(". ").append(srcName)
                    .append(" --[").append(edge.getRelationType()).append("]--> ")
                    .append(tgtName).append("\n");
            indexedExistingEdges.add(edge);
        }

        StringBuilder candidateSummary = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            EdgeCandidate c = candidates.get(i);
            candidateSummary.append("C").append(i).append(". ").append(c.source())
                    .append(" --[").append(c.relation()).append("]--> ")
                    .append(c.target());
            if (c.description() != null) candidateSummary.append(" (").append(c.description()).append(")");
            if (c.validFrom() != null) candidateSummary.append(" [from: ").append(c.validFrom()).append("]");
            if (c.validTo() != null) candidateSummary.append(" [to: ").append(c.validTo()).append("]");
            candidateSummary.append("\n");
        }

        try {
            String prompt = """
                    새 에피소드 내용:
                    %s

                    기존 엣지 목록:
                    %s
                    새 엣지 후보 목록:
                    %s
                    각 새 후보(C0, C1, ...)에 대해 판정하세요:
                    - ADD: 새로운 관계로 추가
                    - UPDATE: 기존 엣지의 관계 보강 (기존 엣지 번호 포함)
                    - NOOP: 이미 존재하거나 불필요

                    기존 엣지(E0, E1, ...)에 대해 새 정보와 모순되는 것을 판정하세요:
                    - DELETE: 새 정보와 모순됨

                    JSON 배열로 응답하세요:
                    [{"id": "C0", "action": "ADD"}, {"id": "E2", "action": "DELETE"}, {"id": "C1", "action": "UPDATE", "existingEdge": "E1"}, {"id": "C2", "action": "NOOP"}]
                    """.formatted(content, existingSummary, candidateSummary);

            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().role("system")
                            .content("지식 그래프 엣지 판정 전문가입니다. JSON만 응답하세요.").build(),
                    ChatMessage.builder().role("user").content(prompt).build()
            );

            ChatCompletionResponse response = client.chatCompletion(messages, 0.1);
            if (response.getChoices() == null || response.getChoices().isEmpty()) return;

            String jsonStr = extractJson(response.getChoices().getFirst().getMessage().getContent(), "[", "]");
            if (jsonStr == null) return;

            JsonNode decisions = objectMapper.readTree(jsonStr);
            if (!decisions.isArray()) return;

            int addCount = 0, updateCount = 0, deleteCount = 0;

            for (JsonNode decision : decisions) {
                String id = decision.has("id") ? decision.get("id").asText() : "";
                String action = decision.has("action") ? decision.get("action").asText() : "NOOP";

                if (id.startsWith("C")) {
                    int idx = parseIndex(id.substring(1));
                    if (idx < 0 || idx >= candidates.size()) continue;
                    EdgeCandidate candidate = candidates.get(idx);

                    switch (action.toUpperCase()) {
                        case "ADD" -> {
                            applyEdgeAdd(candidate, sourceId, entityByName);
                            addCount++;
                        }
                        case "UPDATE" -> {
                            String existingEdgeRef = decision.has("existingEdge") ? decision.get("existingEdge").asText() : null;
                            if (existingEdgeRef != null && existingEdgeRef.startsWith("E")) {
                                int eIdx = parseIndex(existingEdgeRef.substring(1));
                                if (eIdx >= 0 && eIdx < indexedExistingEdges.size()) {
                                    knowledgeGraph.invalidateEdge(indexedExistingEdges.get(eIdx).getId());
                                }
                            }
                            applyEdgeAdd(candidate, sourceId, entityByName);
                            updateCount++;
                        }
                    }
                } else if (id.startsWith("E")) {
                    int idx = parseIndex(id.substring(1));
                    if (idx < 0 || idx >= indexedExistingEdges.size()) continue;

                    if ("DELETE".equalsIgnoreCase(action)) {
                        knowledgeGraph.invalidateEdge(indexedExistingEdges.get(idx).getId());
                        deleteCount++;
                    }
                }
            }

            log.debug("[IngestionPipeline] Edge judge for sourceId {}: +{} ~{} -{}", sourceId, addCount, updateCount, deleteCount);
        } catch (Exception e) {
            log.warn("[IngestionPipeline] Edge judge failed: {}", e.getMessage());
            for (EdgeCandidate c : candidates) {
                applyEdgeAdd(c, sourceId, entityByName);
            }
        }
    }

    private void applyEdgeAdd(EdgeCandidate candidate, String sourceId,
                              Map<String, MemoryEntityEntity> entityByName) {
        var sourceEntity = entityByName.get(candidate.source().toLowerCase());
        if (sourceEntity == null) {
            sourceEntity = knowledgeGraph.addEntity(candidate.source(), "concept", null);
            entityByName.put(candidate.source().toLowerCase(), sourceEntity);
        }
        var targetEntity = entityByName.get(candidate.target().toLowerCase());
        if (targetEntity == null) {
            targetEntity = knowledgeGraph.addEntity(candidate.target(), "concept", null);
            entityByName.put(candidate.target().toLowerCase(), targetEntity);
        }
        LocalDateTime parsedFrom = parseDateTime(candidate.validFrom());
        LocalDateTime parsedTo = parseDateTime(candidate.validTo());
        knowledgeGraph.addEdge(sourceEntity.getId(), targetEntity.getId(), candidate.relation(),
                sourceId, candidate.weight(), parsedFrom, parsedTo);
    }

    private LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            try {
                return java.time.LocalDate.parse(text).atStartOfDay();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    // ── Utility ──

    private String extractJson(String text, String openBracket, String closeBracket) {
        if (text == null) return null;
        String str = text.trim();
        int start = str.indexOf(openBracket);
        int end = str.lastIndexOf(closeBracket);
        if (start >= 0 && end > start) {
            return str.substring(start, end + 1);
        }
        return null;
    }

    private int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(HashMap.class, String.class, String.class));
        } catch (Exception e) {
            return Map.of();
        }
    }
}
