package me.taromati.almah.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.chat.AgentMessageSavedEvent;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.retrospect.SessionArchivedEvent;
import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import me.taromati.almah.agent.db.entity.AgentSessionEntity;
import me.taromati.almah.agent.db.repository.AgentMessageRepository;
import me.taromati.almah.agent.db.repository.AgentSessionRepository;
import me.taromati.almah.core.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentSessionService {

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentConfigProperties config;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 활성 세션 조회 (없으면 자동 생성). 유휴 타임아웃 초과 시 자동 아카이브 + 새 세션 생성.
     */
    @Transactional("agentTransactionManager")
    public AgentSessionEntity getOrCreateActiveSession(String channelId) {
        return sessionRepository.findByChannelIdAndActiveTrue(channelId)
                .map(session -> {
                    if (shouldArchive(session)) {
                        return archiveAndCreateNew(session);
                    }
                    return session;
                })
                .orElseGet(() -> createNewSession(channelId));
    }

    /**
     * 활성 세션 조회 (없으면 null 반환). 자율 사고에서 주 세션 참조용.
     */
    @Transactional(value = "agentTransactionManager", readOnly = true)
    public AgentSessionEntity findActiveSession(String channelId) {
        return sessionRepository.findByChannelIdAndActiveTrue(channelId).orElse(null);
    }

    /**
     * 기존 세션 비활성화 → summary carry → 새 세션 생성 → 오래된 세션 정리.
     * RoutineOrchestrator에서도 호출됨.
     */
    @Transactional("agentTransactionManager")
    public AgentSessionEntity archiveAndCreateNew(AgentSessionEntity oldSession) {
        String channelId = oldSession.getChannelId();
        // routine은 summary carry 안 함
        String carriedSummary = channelId.startsWith("routine:") ? null : oldSession.getSummary();

        // 기존 세션 비활성화
        oldSession.setActive(false);
        sessionRepository.save(oldSession);

        // summary carry → 새 세션 생성
        AgentSessionEntity newSession = AgentSessionEntity.builder()
                .channelId(channelId)
                .title(channelId.startsWith("routine:") ? "ROUTINE" : null)
                .summary(carriedSummary)
                .active(true)
                .build();
        AgentSessionEntity saved = sessionRepository.save(newSession);

        log.info("[AgentSession] Archived session {} (idle timeout), new session: {} (summary {})",
                oldSession.getId(), saved.getId(),
                carriedSummary != null ? "carried" : "none");

        // 세션 회고 이벤트 발행
        eventPublisher.publishEvent(new SessionArchivedEvent(oldSession.getId(), channelId));

        // 오래된 비활성 세션 정리
        cleanupOldSessions(channelId);

        return saved;
    }

    /**
     * 세션 아카이브 판단: routine 세션 제외, 유휴 타임아웃 초과 여부.
     */
    private boolean shouldArchive(AgentSessionEntity session) {
        if (session.getChannelId().startsWith("routine:")) return false;
        int timeoutMinutes = config.getSession().getSessionIdleTimeoutMinutes();
        if (timeoutMinutes <= 0) return false;
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        return session.getUpdatedAt().isBefore(cutoff);
    }

    /**
     * 채널당 비활성 세션을 최대 maxKeep개만 유지. 초과분 삭제 (메시지 포함).
     */
    private void cleanupOldSessions(String channelId) {
        int maxKeep = channelId.startsWith("routine:")
                ? 3  // routine은 3개만
                : config.getSession().getMaxInactiveSessions();
        List<AgentSessionEntity> sessions = sessionRepository
                .findByChannelIdAndActiveFalseOrderByUpdatedAtDesc(channelId);
        if (sessions.size() <= maxKeep) return;

        List<AgentSessionEntity> toDelete = sessions.subList(maxKeep, sessions.size());
        for (AgentSessionEntity old : toDelete) {
            messageRepository.deleteBySessionId(old.getId());
            sessionRepository.delete(old);
        }
        log.info("[AgentSession] Cleaned up {} old sessions for channel {}", toDelete.size(), channelId);
    }

    /**
     * 메시지 저장
     */
    @Transactional("agentTransactionManager")
    public AgentMessageEntity saveMessage(String sessionId, String role, String content,
                                           String toolCallId, String toolCalls) {
        return saveMessage(sessionId, role, content, toolCallId, toolCalls, null);
    }

    /**
     * 메시지 저장 (모델명 포함)
     */
    @Transactional("agentTransactionManager")
    public AgentMessageEntity saveMessage(String sessionId, String role, String content,
                                           String toolCallId, String toolCalls, String model) {
        AgentMessageEntity message = AgentMessageEntity.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .toolCallId(toolCallId)
                .toolCalls(toolCalls)
                .model(model)
                .build();
        AgentMessageEntity saved = messageRepository.save(message);

        // 세션 updatedAt 갱신
        sessionRepository.findById(sessionId).ifPresent(sessionRepository::save);

        // Memory 인제스트용 이벤트 발행 (user/assistant, 내용 있을 때만)
        publishMessageEvent(saved);

        return saved;
    }

    /**
     * 복수 메시지 일괄 저장 (tool chain: assistant(tool_calls) + tool responses)
     */
    @Transactional("agentTransactionManager")
    public void saveMessages(List<AgentMessageEntity> messages) {
        if (messages.isEmpty()) return;
        messageRepository.saveAll(messages);

        // 세션 updatedAt 갱신
        String sessionId = messages.getFirst().getSessionId();
        sessionRepository.findById(sessionId).ifPresent(sessionRepository::save);

        // Memory 인제스트용 이벤트 발행
        for (AgentMessageEntity msg : messages) {
            publishMessageEvent(msg);
        }
    }

    private void publishMessageEvent(AgentMessageEntity msg) {
        String role = msg.getRole();
        if (("user".equals(role) || "assistant".equals(role))
                && msg.getContent() != null && !msg.getContent().isBlank()) {
            eventPublisher.publishEvent(new AgentMessageSavedEvent(
                    msg.getId(), msg.getSessionId(), role, msg.getContent()));
        }
    }

    /**
     * 세션 메시지 조회
     */
    @Transactional(value = "agentTransactionManager", readOnly = true)
    public List<AgentMessageEntity> getMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * 메시지 수 조회
     */
    @Transactional(value = "agentTransactionManager", readOnly = true)
    public int getMessageCount(String sessionId) {
        return messageRepository.countBySessionId(sessionId);
    }

    /**
     * 새 세션 생성 + 기존 활성 세션 비활성화
     */
    @Transactional("agentTransactionManager")
    public AgentSessionEntity createNewSession(String channelId) {
        // 기존 활성 세션 비활성화
        sessionRepository.findByChannelIdAndActiveTrue(channelId)
                .ifPresent(existing -> {
                    existing.setActive(false);
                    sessionRepository.save(existing);
                });

        AgentSessionEntity session = AgentSessionEntity.builder()
                .channelId(channelId)
                .title(channelId.startsWith("routine:") ? "ROUTINE" : null)
                .active(true)
                .build();
        AgentSessionEntity saved = sessionRepository.save(session);
        log.info("[AgentSession] Created new session: {} for channel: {}", saved.getId(), channelId);
        return saved;
    }

    /**
     * 마지막 assistant 응답 이후의 user 메시지 삭제
     * LLM 호출 실패 시 DB 상태를 마지막 성공 시점으로 복구
     */
    @Transactional("agentTransactionManager")
    public int deleteUnansweredUserMessages(String sessionId) {
        int deleted = messageRepository.deleteUnansweredUserMessages(sessionId);
        if (deleted > 0) {
            log.info("[AgentSession] Deleted {} unanswered user messages from session: {}", deleted, sessionId);
        }
        return deleted;
    }

    /**
     * 세션 초기화 (메시지 전체 삭제 + summary + toolApprovals 초기화)
     */
    @Transactional("agentTransactionManager")
    public void resetSession(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setSummary(null);
            session.setCompactionCount(0);
            session.setTitle(null);
            session.setToolApprovals(null);
            session.setLlmModel(null);
            sessionRepository.save(session);
        });
        log.info("[AgentSession] Reset session: {}", sessionId);
    }

    /**
     * 세션 LLM 모델 갱신
     */
    @Transactional("agentTransactionManager")
    public void updateLlmModel(String sessionId, String llmModel) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setLlmModel(llmModel);
            sessionRepository.save(session);
        });
    }

    /**
     * 세션 제목 자동 생성 (첫 유저 메시지에서 추출)
     */
    @Transactional("agentTransactionManager")
    public void generateTitle(String sessionId, String firstUserMessage) {
        String title = StringUtils.truncateRaw(firstUserMessage, 50);
        sessionRepository.findById(sessionId).ifPresent(s -> {
            if (s.getTitle() == null) {
                s.setTitle(title);
                sessionRepository.save(s);
            }
        });
    }

    /**
     * 세션 삭제 (메시지 포함)
     */
    @Transactional("agentTransactionManager")
    public void deleteSession(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("[AgentSession] Deleted session: {}", sessionId);
    }

    /**
     * 전체 세션 삭제 (메시지 포함)
     */
    @Transactional("agentTransactionManager")
    public void deleteAllSessions() {
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        log.info("[AgentSession] Deleted all sessions");
    }

    /**
     * 세션 목록 조회 (채널별)
     */
    @Transactional(value = "agentTransactionManager", readOnly = true)
    public List<AgentSessionEntity> listSessions(String channelId) {
        return sessionRepository.findByChannelIdOrderByUpdatedAtDesc(channelId);
    }

    /**
     * 세션 전환
     */
    @Transactional("agentTransactionManager")
    public AgentSessionEntity switchSession(String channelId, String sessionId) {
        // 기존 활성 세션 비활성화
        sessionRepository.findByChannelIdAndActiveTrue(channelId)
                .ifPresent(existing -> {
                    existing.setActive(false);
                    sessionRepository.save(existing);
                });

        // 대상 세션 활성화
        AgentSessionEntity target = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        target.setActive(true);
        sessionRepository.save(target);

        log.info("[AgentSession] Switched to session: {} in channel: {}", sessionId, channelId);
        return target;
    }
}
