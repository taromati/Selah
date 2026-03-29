package me.taromati.almah.agent.chat;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import me.taromati.almah.agent.db.entity.AgentSessionEntity;
import me.taromati.almah.agent.permission.ChatRejection;
import me.taromati.almah.agent.service.AgentCompactionService;
import me.taromati.almah.agent.service.AgentSessionService;
import me.taromati.almah.llm.client.LlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 채널별 활성 세션 1개 관리, 유휴 타임아웃, summary carry.
 * AgentSessionService를 래핑하여 대화 전용 세션 관리를 제공합니다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ChatSessionManager {

    private final AgentSessionService sessionService;
    private final AgentCompactionService compactionService;
    private final AgentConfigProperties config;
    private final ChatRejection chatRejection;

    /** 채널별 취소 플래그 (L6: 외부 취소 지원) */
    private final ConcurrentHashMap<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /** 채널별 동시성 상태 */
    private final ConcurrentHashMap<String, ChannelState> channelStates = new ConcurrentHashMap<>();

    /** 이어하기 버튼 대기 중 인터럽트용 */
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> continuationFutures = new ConcurrentHashMap<>();

    @Autowired
    public ChatSessionManager(AgentSessionService sessionService,
                               AgentCompactionService compactionService,
                               AgentConfigProperties config,
                               ChatRejection chatRejection) {
        this.sessionService = sessionService;
        this.compactionService = compactionService;
        this.config = config;
        this.chatRejection = chatRejection;
    }

    /**
     * 채널별 활성 세션 가져오기 (없으면 생성)
     */
    public AgentSessionEntity getOrCreateSession(String channelId) {
        return sessionService.getOrCreateActiveSession(channelId);
    }

    /**
     * 세션 리셋 — cancel 플래그 + 채널 상태 포함 전체 초기화.
     */
    public void resetSession(String channelId) {
        cancelFlags.remove(channelId);
        channelStates.remove(channelId);
        continuationFutures.remove(channelId);
        if (sessionService != null) {
            AgentSessionEntity session = sessionService.findActiveSession(channelId);
            if (session != null) {
                sessionService.resetSession(session.getId());
            }
        }
    }

    /**
     * 아카이브 + 새 세션 (컨텍스트 소진 시)
     */
    public AgentSessionEntity archiveAndCreateNew(AgentSessionEntity session) {
        chatRejection.clearSessionCache(session.getChannelId());
        return sessionService.archiveAndCreateNew(session);
    }

    /**
     * 메시지 저장 위임
     */
    public AgentMessageEntity saveMessage(String sessionId, String role, String content,
                                           String toolCallId, String toolCalls, String model) {
        return sessionService.saveMessage(sessionId, role, content, toolCallId, toolCalls, model);
    }

    /**
     * 복수 메시지 일괄 저장 위임
     */
    public void saveMessages(List<AgentMessageEntity> messages) {
        sessionService.saveMessages(messages);
    }

    /**
     * 메시지 조회 위임
     */
    public List<AgentMessageEntity> getMessages(String sessionId) {
        return sessionService.getMessages(sessionId);
    }

    /**
     * LLM 호출 실패 시 미응답 user 메시지 삭제 (DB 상태 복구)
     */
    public int deleteUnansweredUserMessages(String sessionId) {
        return sessionService.deleteUnansweredUserMessages(sessionId);
    }

    /**
     * 컨텍스트 압축 (컨텍스트 창 초과 시).
     * AgentCompactionService는 sessionId + EffectiveSessionConfig을 받으므로
     * 프로바이더 능력치를 resolve하여 전달합니다.
     */
    public void compactIfNeeded(AgentSessionEntity session, LlmClient client) {
        var effective = config.resolveSessionConfig(client.getCapabilities());
        compactionService.compactIfNeeded(session.getId(), client, effective);
    }

    // ─── 취소 플래그 (L6) ───

    /**
     * 채널의 진행 중인 Tool Calling 루프 취소 요청.
     * continuation 대기 중이면 즉시 완료하여 최대 2분 대기 방지.
     */
    public void requestCancel(String channelId) {
        cancelFlags.put(channelId, true);
        completeContinuationFuture(channelId, false);
    }

    /**
     * 취소 요청 여부 확인 및 소비 (한 번 읽으면 플래그 제거).
     */
    public boolean isCancelled(String channelId) {
        return Boolean.TRUE.equals(cancelFlags.remove(channelId));
    }

    /**
     * cancel flag 명시적 제거 — stale flag 방지.
     */
    public void clearCancel(String channelId) {
        cancelFlags.remove(channelId);
    }

    // ─── 채널 동시성 제어 ───

    /**
     * 채널 acquire 시도. idle이면 acquire + true 반환.
     * busy이면 pending 큐에 메시지 추가 + false 반환 (cancel 안 함 — 실시간 루프에서 소비).
     */
    public boolean tryAcquireOrQueue(String channelId, String message) {
        ChannelState state = channelStates.computeIfAbsent(channelId, k -> new ChannelState());
        synchronized (state.lock) {
            if (!state.busy) {
                state.busy = true;
                return true;
            }
            state.pendingMessages.add(message);
        }
        return false;
    }

    /**
     * 대기 메시지 drain 또는 채널 release.
     * pending이 있으면 모두 꺼내서 반환 (채널 busy 유지).
     * pending이 없으면 busy=false로 전환하고 빈 리스트 반환 (채널 release).
     */
    public List<String> drainPendingOrRelease(String channelId) {
        ChannelState state = channelStates.get(channelId);
        if (state == null) return List.of();
        synchronized (state.lock) {
            if (state.pendingMessages.isEmpty()) {
                state.busy = false;
                return List.of();
            }
            List<String> drained = new ArrayList<>(state.pendingMessages);
            state.pendingMessages.clear();
            return drained;
        }
    }

    /**
     * release 없이 pending 메시지만 drain. 실시간 루프에서 매 라운드마다 호출.
     * 메시지가 있으면 "\n"으로 합산하여 반환. 없으면 null.
     * 채널 busy 상태는 유지된다.
     */
    public String drainPending(String channelId) {
        ChannelState state = channelStates.get(channelId);
        if (state == null) return null;
        synchronized (state.lock) {
            if (state.pendingMessages.isEmpty()) return null;
            List<String> drained = new ArrayList<>(state.pendingMessages);
            state.pendingMessages.clear();
            return drained.size() == 1 ? drained.getFirst() : String.join("\n", drained);
        }
    }

    /**
     * 에러 복구용 채널 강제 해제 — busy=false + pending 폐기 + cancel flag clear.
     */
    public void forceRelease(String channelId) {
        cancelFlags.remove(channelId);
        ChannelState state = channelStates.get(channelId);
        if (state == null) return;
        synchronized (state.lock) {
            state.busy = false;
            state.pendingMessages.clear();
        }
    }

    /**
     * !초기화 시 대기큐 비우기.
     */
    public void clearPendingMessages(String channelId) {
        ChannelState state = channelStates.get(channelId);
        if (state == null) return;
        synchronized (state.lock) {
            state.pendingMessages.clear();
        }
    }

    /**
     * 대기 메시지 존재 여부 (HANDOFF 생성 판단용).
     */
    public boolean hasPendingMessages(String channelId) {
        ChannelState state = channelStates.get(channelId);
        if (state == null) return false;
        return !state.pendingMessages.isEmpty();
    }

    // ─── Continuation Future ───

    /**
     * continuation 대기 등록.
     */
    public void setContinuationFuture(String channelId, CompletableFuture<Boolean> future) {
        continuationFutures.put(channelId, future);
    }

    /**
     * continuation 대기 해제.
     */
    public void clearContinuationFuture(String channelId) {
        continuationFutures.remove(channelId);
    }

    private void completeContinuationFuture(String channelId, boolean value) {
        CompletableFuture<Boolean> future = continuationFutures.remove(channelId);
        if (future != null) {
            future.complete(value);
        }
    }

    // ─── Inner class ───

    private static class ChannelState {
        final Object lock = new Object();
        boolean busy = false;
        final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    }
}
