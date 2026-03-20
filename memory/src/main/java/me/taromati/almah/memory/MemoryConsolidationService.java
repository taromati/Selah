package me.taromati.almah.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.embedding.EmbeddingService;
import me.taromati.almah.llm.embedding.EmbeddingUtil;
import me.taromati.almah.memory.config.MemoryConfigProperties;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.entity.MemoryEdgeEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.almah.memory.db.repository.MemoryEdgeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryConsolidationService {

    private final MemoryConfigProperties config;
    private final MemoryChunkRepository chunkRepository;
    private final MemoryEdgeRepository edgeRepository;
    private final ChunkStore chunkStore;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final LlmClientResolver llmClientResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryConsolidationService(MemoryConfigProperties config,
                                       MemoryChunkRepository chunkRepository,
                                       MemoryEdgeRepository edgeRepository,
                                       ChunkStore chunkStore,
                                       ChunkingService chunkingService,
                                       EmbeddingService embeddingService,
                                       LlmClientResolver llmClientResolver) {
        this.config = config;
        this.chunkRepository = chunkRepository;
        this.edgeRepository = edgeRepository;
        this.chunkStore = chunkStore;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.llmClientResolver = llmClientResolver;
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void consolidate() {
        log.info("[MemoryConsolidation] Starting consolidation");
        consolidateOldChunks();
        log.info("[MemoryConsolidation] Consolidation completed");
    }

    private void consolidateOldChunks() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(config.getMaxChunkAgeDays());
        List<MemoryChunkEntity> oldChunks = chunkRepository
                .findByCreatedAtBeforeAndGraphProcessedTrueAndConsolidatedFalse(cutoff);

        if (oldChunks.isEmpty()) {
            log.debug("[MemoryConsolidation] No chunks to consolidate");
            return;
        }

        log.info("[MemoryConsolidation] Found {} chunks to consolidate", oldChunks.size());

        // sourceId 단위로 그룹화 후 → 주(week) 단위로 재그룹화
        Map<String, List<MemoryChunkEntity>> bySourceId = oldChunks.stream()
                .collect(Collectors.groupingBy(MemoryChunkEntity::getSourceId));

        // 주 단위 그룹화: 각 sourceId 그룹의 첫 번째 청크 시간 기준
        Map<String, List<MemoryChunkEntity>> weekGroups = new LinkedHashMap<>();
        for (var entry : bySourceId.entrySet()) {
            List<MemoryChunkEntity> chunks = entry.getValue();
            chunks.sort(Comparator.comparingInt(MemoryChunkEntity::getChunkIndex));
            LocalDateTime groupTime = chunks.getFirst().getCreatedAt();
            int year = groupTime.getYear();
            int week = groupTime.get(WeekFields.ISO.weekOfWeekBasedYear());
            String weekKey = year + "-W" + String.format("%02d", week);
            weekGroups.computeIfAbsent(weekKey, k -> new ArrayList<>()).addAll(chunks);
        }

        LlmClient client = llmClientResolver.resolve(config.getLlmProvider());

        for (var entry : weekGroups.entrySet()) {
            try {
                processWeekGroup(entry.getKey(), entry.getValue(), client);
            } catch (Exception e) {
                log.warn("[MemoryConsolidation] Failed to consolidate week {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
    }

    private void processWeekGroup(String weekKey, List<MemoryChunkEntity> chunks, LlmClient client) {
        if (chunks.size() < 2) return;

        chunks.sort(Comparator.comparing(MemoryChunkEntity::getCreatedAt));

        LocalDateTime periodStart = chunks.getFirst().getCreatedAt();
        LocalDateTime periodEnd = chunks.getLast().getCreatedAt();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M/d");

        // LLM 요약 (TX 외부)
        StringBuilder combined = new StringBuilder();
        for (MemoryChunkEntity chunk : chunks) {
            combined.append(chunk.getContent()).append("\n---\n");
        }

        String summarizePrompt = String.format("""
                다음은 %s~%s 기간의 대화 기록입니다.
                핵심 사실, 감정, 결정 사항만 간결하게 요약하세요 (200자 이내).
                원문의 구체적 표현은 유지하되 반복/중복은 제거하세요.

                %s""",
                periodStart.format(fmt), periodEnd.format(fmt), combined.toString());

        String summary;
        try {
            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().role("system")
                            .content("대화 내용을 요약하는 전문가입니다. 간결하고 정확하게 요약하세요.")
                            .build(),
                    ChatMessage.builder().role("user").content(summarizePrompt).build()
            );
            ChatCompletionResponse response = client.chatCompletion(messages, 0.3);
            if (response.getChoices() == null || response.getChoices().isEmpty()) {
                log.warn("[MemoryConsolidation] LLM returned empty response for week {}", weekKey);
                return;
            }
            summary = response.getChoices().getFirst().getMessage().getContent();
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] LLM summarization failed for week {}: {}", weekKey, e.getMessage());
            return;
        }

        // 요약을 다시 청킹
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("type", "consolidated");
        metadata.put("sourceCount", String.valueOf(chunks.size()));
        metadata.put("periodStart", periodStart.toString());
        metadata.put("periodEnd", periodEnd.toString());

        String metadataJson = serializeMetadata(metadata);
        List<ChunkData> summaryChunks = chunkingService.chunk(summary, metadataJson);
        if (summaryChunks.isEmpty()) return;

        // 요약 청크에 metadata 설정
        summaryChunks = summaryChunks.stream()
                .map(c -> new ChunkData(c.content(), metadata, c.tokenCount()))
                .toList();

        // 임베딩 생성 (TX 외부)
        List<byte[]> embeddings = new ArrayList<>();
        for (ChunkData chunk : summaryChunks) {
            try {
                float[] vector = embeddingService.embed(chunk.content());
                embeddings.add(vector != null ? EmbeddingUtil.floatArrayToBytes(vector) : null);
            } catch (Exception e) {
                log.debug("[MemoryConsolidation] Embedding failed: {}", e.getMessage());
                embeddings.add(null);
            }
        }

        saveConsolidatedChunks(summaryChunks, embeddings, chunks);
    }

    @Transactional("memoryTransactionManager")
    public void saveConsolidatedChunks(List<ChunkData> summaryChunks, List<byte[]> embeddings,
                                        List<MemoryChunkEntity> originals) {
        // 요약 청크 저장 (consolidated=true)
        String newSourceId = me.taromati.almah.core.util.UUIDv7.generate();
        List<MemoryChunkEntity> saved = chunkStore.saveAll(summaryChunks, newSourceId, embeddings);

        // consolidated 플래그 설정
        for (MemoryChunkEntity chunk : saved) {
            chunk.setConsolidated(true);
            chunk.setGraphProcessed(true);
        }
        chunkRepository.saveAll(saved);

        // 엣지 sourceId 업데이트: 원본의 sourceId들 → 새 sourceId로
        Set<String> originalSourceIds = originals.stream()
                .map(MemoryChunkEntity::getSourceId)
                .collect(Collectors.toSet());
        List<MemoryEdgeEntity> edges = edgeRepository.findBySourceIdIn(new ArrayList<>(originalSourceIds));
        for (MemoryEdgeEntity edge : edges) {
            edge.setSourceId(newSourceId);
        }
        edgeRepository.saveAll(edges);

        // 원본 청크 삭제
        List<String> originalIds = originals.stream().map(MemoryChunkEntity::getId).toList();
        chunkStore.deleteChunks(originalIds);

        log.info("[MemoryConsolidation] Consolidated {} chunks into {} new chunks (sourceId={})",
                originals.size(), saved.size(), newSourceId);
    }

    private String serializeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
