package me.taromati.almah.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import me.taromati.almah.agent.db.repository.AgentMessageRepository;
import me.taromati.almah.agent.db.repository.AgentSessionRepository;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Agent 자동 컴팩션 서비스
 * 토큰 추정치가 budget * compactionRatio를 초과하면 LLM 요약을 생성하고 오래된 메시지를 삭제합니다.
 *
 * <h2>TX 3단계 분리 패턴</h2>
 * <ol>
 *   <li>짧은 TX: 메시지 읽기 + 토큰 체크 (readCompactionData)</li>
 *   <li>TX 없음: LLM 요약 호출 (generateSummary)</li>
 *   <li>짧은 TX: summary 저장 + 오래된 메시지 삭제 (applyCompaction)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentCompactionService {

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentConfigProperties config;
    private final PersistentContextReader persistentContextReader;
    private final ObjectProvider<AgentCompactionService> selfProvider;

    private AgentCompactionService self() {
        return selfProvider.getObject();
    }

    private static final int MESSAGE_OVERHEAD_CHARS = 10;
    private static final double SAFETY_MARGIN = 1.2;

    /**
     * 컴팩션 필요 여부 확인 + 실행
     */
    public void compactIfNeeded(String sessionId, LlmClient client,
                                 AgentConfigProperties.EffectiveSessionConfig effective) {
        // Step 1: 짧은 TX — 세션 + 메시지 로드 + 토큰 체크
        CompactionData data = self().readCompactionData(sessionId, effective);
        if (data == null) return;

        // Step 2: TX 없음 — LLM 요약
        String summary = generateSummary(data.existingSummary(), data.oldMessages(), client);
        if (summary == null) {
            log.warn("[AgentCompaction] Summary generation failed, skipping compaction");
            return;
        }

        // Step 3: 짧은 TX — summary 저장 + 오래된 메시지 삭제
        self().applyCompaction(sessionId, summary, effective.recentKeep());
    }

    @Transactional(value = "agentTransactionManager", readOnly = true)
    public CompactionData readCompactionData(String sessionId,
                                              AgentConfigProperties.EffectiveSessionConfig effective) {
        var session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return null;

        var messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int recentKeep = effective.recentKeep();
        if (messages.size() <= recentKeep) return null;

        // 토큰 기반 체크
        int estimatedTokens = estimateSessionTokens(messages, session.getSummary(), effective.charsPerToken());
        int contextBudget = Math.max(effective.contextWindow() - effective.maxTokens(), 1);
        int compactionLimit = (int) (contextBudget * effective.compactionRatio());

        if (estimatedTokens <= compactionLimit) return null;

        log.info("[AgentCompaction] Token threshold exceeded: ~{} tokens > {} limit (session {})",
                estimatedTokens, compactionLimit, sessionId);

        var oldMessages = messages.subList(0, messages.size() - recentKeep);
        return new CompactionData(session.getSummary(), List.copyOf(oldMessages));
    }

    @Transactional("agentTransactionManager")
    public void applyCompaction(String sessionId, String summary, int recentKeep) {
        int deleted = messageRepository.deleteOldMessages(sessionId, recentKeep);

        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setSummary(summary);
            s.setCompactionCount(s.getCompactionCount() + 1);
            sessionRepository.save(s);
        });

        log.info("[AgentCompaction] Compacted session {}: deleted {} messages", sessionId, deleted);
    }

    /**
     * LLM 요약 생성 (TX 외부)
     */
    private String generateSummary(String existingSummary, List<AgentMessageEntity> messages, LlmClient client) {
        StringBuilder text = new StringBuilder();
        if (existingSummary != null) {
            text.append("[이전 요약]\n").append(existingSummary).append("\n\n");
        }
        text.append("[새 대화 내용]\n");
        for (var msg : messages) {
            if ("tool".equals(msg.getRole())) continue;
            text.append(msg.getRole()).append(": ")
                    .append(StringUtils.truncateRaw(msg.getContent(), 500)).append("\n");
        }

        var request = List.of(
                ChatMessage.builder().role("system")
                        .content("기존 요약과 새 대화를 **하나의 통합 요약으로 교체**하세요.\n" +
                                "기존 요약에서 새 대화로 업데이트된 부분은 최신 정보로 대체하세요.\n" +
                                "반드시 포함: 1) 핵심 결정사항 2) 진행 중인 작업 3) 사용자 선호도 " +
                                "4) 이미 수행한 Routine 항목 (중복 방지용)\n" +
                                "**출력은 500자 이내로 제한하세요.**")
                        .build(),
                ChatMessage.builder().role("user").content(text.toString()).build()
        );

        try {
            var response = client.chatCompletion(request, SamplingParams.withTemperature(0.3), null, null);
            return response.getContent();
        } catch (Exception e) {
            log.error("[AgentCompaction] Summary generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * DB 메시지의 토큰 수를 문자 수 기반으로 추정
     */
    private int estimateSessionTokens(List<AgentMessageEntity> messages, String summary, int charsPerToken) {
        charsPerToken = Math.max(charsPerToken, 1);
        int totalChars = config.getSystemPrompt().length();
        totalChars += persistentContextReader.estimatePersistentContextChars();
        if (summary != null) totalChars += summary.length();
        for (var msg : messages) {
            totalChars += MESSAGE_OVERHEAD_CHARS;
            if (msg.getContent() != null) totalChars += msg.getContent().length();
            if (msg.getToolCalls() != null) totalChars += msg.getToolCalls().length();
        }
        return (int) Math.ceil((double) totalChars / charsPerToken * SAFETY_MARGIN);
    }

    private record CompactionData(String existingSummary, List<AgentMessageEntity> oldMessages) {}
}
