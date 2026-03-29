package me.taromati.almah.agent.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import me.taromati.almah.agent.db.entity.AgentSessionEntity;
import me.taromati.almah.agent.permission.PermissionGate;
import me.taromati.almah.agent.service.AgentBusyState;
import me.taromati.almah.agent.service.AgentContextBuilder;
import me.taromati.almah.agent.suggest.SuggestHistory;
import me.taromati.almah.agent.tool.AgentToolContext;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.llm.tool.LoopCallbacks;
import me.taromati.almah.llm.tool.LoopContext;
import me.taromati.almah.llm.tool.StreamingListener;
import me.taromati.almah.llm.tool.ToolCallingService;
import me.taromati.almah.llm.tool.ToolExecutionFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 사용자 메시지 -> LLM + Tool Calling 루프, 예산 관리, 응답 발송.
 * 현행 AgentListener.handleChat()의 핵심 로직을 이관합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ChatOrchestrator {

    private final ObjectMapper objectMapper;

    private final ChatSessionManager sessionManager;
    private final AgentContextBuilder contextBuilder;
    private final AgentConfigProperties config;
    private final LlmClientResolver clientResolver;
    private final ToolCallingService toolCallingService;
    private final PermissionGate permissionGate;

    /** 미응답 제안 기록 (H7 + M14) */
    @Autowired(required = false)
    private SuggestHistory suggestHistory;

    /** 대화 busy 상태 (Agent 5에서 생성 예정) */
    @Autowired(required = false)
    private AgentBusyState busyState;

    /**
     * 사용자 메시지 처리 메인 루프.
     *
     * @return 대화 결과 (null이면 응답 없음)
     */
    public ChatResult handle(String channelId, String userMessage, ChannelRef channel) {
        return handle(channelId, userMessage, channel, null);
    }

    /**
     * 사용자 메시지 처리 (실시간 대화 루프용).
     * onIntermediateText 콜백으로 중간 텍스트를 즉시 전송한다.
     */
    public ChatResult handle(String channelId, String userMessage, ChannelRef channel,
                             Consumer<String> onIntermediateText) {
        if (busyState != null) busyState.setChatBusy(true);
        try {
            return doHandle(channelId, userMessage, channel, onIntermediateText);
        } finally {
            if (busyState != null) busyState.setChatBusy(false);
        }
    }

    /**
     * 사용자 메시지 처리 (SSE 스트리밍 루프용 — LoopCallbacks + StreamingListener).
     * 새 인터페이스 기반. AgentListener에서 StreamingMessageHandler를 직접 전달한다.
     *
     * @param loopCallbacks onIntermediateText + incomingMessagePoll 묶음
     * @param streamingListener SSE 토큰 스트리밍 리스너 (null이면 동기 경로)
     */
    public ChatResult handle(String channelId, String userMessage, ChannelRef channel,
                             LoopCallbacks loopCallbacks, StreamingListener streamingListener) {
        if (busyState != null) busyState.setChatBusy(true);
        try {
            return doHandle(channelId, userMessage, channel, loopCallbacks, streamingListener);
        } finally {
            if (busyState != null) busyState.setChatBusy(false);
        }
    }

    private ChatResult doHandle(String channelId, String userMessage, ChannelRef channel,
                                Consumer<String> onIntermediateText) {
        return doHandle(channelId, userMessage, channel, onIntermediateText, (StreamingListener) null);
    }

    private ChatResult doHandle(String channelId, String userMessage, ChannelRef channel,
                                LoopCallbacks loopCallbacks, StreamingListener streamingListener) {
        Consumer<String> onIntermediateText = loopCallbacks != null ? loopCallbacks.onIntermediateText() : null;
        return doHandleCore(channelId, userMessage, channel, onIntermediateText, loopCallbacks, streamingListener);
    }

    private ChatResult doHandle(String channelId, String userMessage, ChannelRef channel,
                                Consumer<String> onIntermediateText,
                                StreamingListener streamingListener) {
        // 기존 Consumer<String> 경로 → LoopCallbacks로 감싸서 core에 위임
        LoopCallbacks callbacks = onIntermediateText != null
                ? new LoopCallbacks(onIntermediateText, null)
                : null;
        return doHandleCore(channelId, userMessage, channel, onIntermediateText, callbacks, streamingListener);
    }

    private ChatResult doHandleCore(String channelId, String userMessage, ChannelRef channel,
                                    Consumer<String> onIntermediateText,
                                    LoopCallbacks loopCallbacks,
                                    StreamingListener streamingListener) {
        // 0. 미응답 제안에 대한 사용자 응답 기록 (H7 + M14)
        if (suggestHistory != null) {
            suggestHistory.recordPendingResponse(userMessage);
        }

        // 1. 세션 가져오기/생성
        AgentSessionEntity session = sessionManager.getOrCreateSession(channelId);

        // 2. LLM 클라이언트 결정 (세션에 저장된 모델 우선)
        String providerName = config.getLlmProviderName();
        LlmClient client = clientResolver.resolve(providerName);
        String model = session.getLlmModel();

        // 3. 사용자 메시지 저장
        sessionManager.saveMessage(session.getId(), "user", userMessage, null, null, null);

        // 4. AgentToolContext 설정
        AgentToolContext.set(channelId, false, false, client, model, AgentToolContext.ExecutionContext.CHAT);
        AgentToolContext.setCurrentUserMessage(userMessage);

        // 5. 컨텍스트 빌드
        var effective = config.resolveSessionConfig(client.getCapabilities());
        List<AgentMessageEntity> dbMessages = sessionManager.getMessages(session.getId());
        List<ChatMessage> context = contextBuilder.buildContext(
                config.getSystemPrompt(), session, dbMessages, client, effective);

        // 6. 도구 목록 + 필터 생성
        List<String> visibleTools = permissionGate.getCoreTools();
        ToolExecutionFilter filter = permissionGate.createChatFilter(null, channel);

        // 6.5. 도구 예산 안내 (별도 system 메시지로 주입) (M8)
        int maxDurationMinutes = config.getMaxDurationMinutes();
        int warningMinute = Math.max(1, maxDurationMinutes - 1);
        context.add(ChatMessage.builder().role("system")
                .content("[내부 지침] 이 턴의 도구 실행 시간 한도는 최대 " + maxDurationMinutes + "분입니다. "
                        + warningMinute + "분 경과 시 잔여 작업을 정리하세요. "
                        + "이 안내는 사용자에게 보이지 않으므로 응답에 언급하지 마세요.")
                .build());

        // 6.6. 실시간 메시지 수신 안내 (S12 — onIntermediateText가 non-null일 때만)
        if (onIntermediateText != null) {
            context.add(ChatMessage.builder().role("system")
                    .content("[내부 지침] 도구 실행 중 사용자가 새 메시지를 보낼 수 있습니다. " +
                            "새 메시지가 컨텍스트에 나타나면 먼저 간단히 대응한 후 기존 작업을 이어가세요. " +
                            "급한 질문이면 즉시 답변하고, 방향 변경이면 기존 작업을 중단하세요. " +
                            "이 안내는 사용자에게 보이지 않으므로 응답에 언급하지 마세요.")
                    .build());
        }

        // 7. SamplingParams 구성
        SamplingParams params = new SamplingParams(
                effective.maxTokens(), config.getTemperature(),
                config.getTopP(), config.getMinP(),
                config.getFrequencyPenalty(), config.getRepetitionPenalty(), null
        );

        // 8. Tool Calling 루프 (cancelCheck: AtomicBoolean 캐싱으로 consume-once + multi-check 호환)
        var callingConfig = new ToolCallingService.ToolCallingConfig(
                effective.contextWindow(), effective.charsPerToken(), maxDurationMinutes);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Supplier<Boolean> cancelCheck = () -> {
            if (cancelled.get()) return true;
            if (sessionManager.isCancelled(channelId)) {
                cancelled.set(true);
                return true;
            }
            return false;
        };

        // incomingMessagePoll: LoopCallbacks에 있으면 그대로 사용, 없으면 onIntermediateText 기반 생성
        Supplier<String> incomingMessagePoll;
        if (loopCallbacks != null && loopCallbacks.incomingMessagePoll() != null) {
            incomingMessagePoll = loopCallbacks.incomingMessagePoll();
        } else if (onIntermediateText != null) {
            incomingMessagePoll = () -> {
                String msg = sessionManager.drainPending(channelId);
                if (msg != null) {
                    sessionManager.saveMessage(session.getId(), "user", msg, null, null, null);
                }
                return msg;
            };
        } else {
            incomingMessagePoll = null;
        }

        ToolCallingService.ToolCallingResult result;
        try {
            if (streamingListener != null || onIntermediateText != null) {
                // LoopContext 기반 경로 (새 인터페이스)
                LoopCallbacks effectiveCallbacks = new LoopCallbacks(onIntermediateText, incomingMessagePoll);
                LoopContext loopContext = new LoopContext(callingConfig, cancelCheck, null, effectiveCallbacks);
                result = toolCallingService.chatWithTools(
                        context, params, visibleTools, filter, client, model,
                        loopContext, streamingListener);
            } else {
                // 기존 8인자 오버로드
                result = toolCallingService.chatWithTools(
                        context, params, visibleTools, filter, client, model, callingConfig,
                        cancelCheck);
            }
        } catch (Exception e) {
            // LLM 호출 실패 시 미응답 user 메시지 삭제하여 DB 상태 복구
            sessionManager.deleteUnansweredUserMessages(session.getId());
            throw e;
        } finally {
            AgentToolContext.clearCurrentUserMessage();
            AgentToolContext.clear();
        }

        // 9. 취소 여부에 따른 후처리 분기
        var terminationReason = result.terminationReason();

        if (terminationReason == ToolCallingService.TerminationReason.CANCELLED) {
            if (result.intermediateMessages() != null && !result.intermediateMessages().isEmpty()) {
                saveIntermediateMessages(session.getId(), result.intermediateMessages());
                sessionManager.saveMessage(session.getId(), "assistant", "[작업이 중단되었습니다]",
                        null, null, result.model());
            } else {
                sessionManager.deleteUnansweredUserMessages(session.getId());
            }
            return new ChatResult(null, result.images(), false, null, terminationReason);
        }

        // 10. 정상 경로: 중간 메시지 저장
        saveIntermediateMessages(session.getId(), result.intermediateMessages());

        // 11. 응답 저장
        String responseText = result.textResponse();
        if (responseText != null && !responseText.isBlank()) {
            sessionManager.saveMessage(session.getId(), "assistant", responseText,
                    null, null, result.model());
        }

        // 12. 컨텍스트 압축 확인
        sessionManager.compactIfNeeded(session, client);

        // 13. 종료 분류 (HANDOFF는 AgentListener에서 최종 시점에 생성)
        var classification = ChatTerminationClassifier.classify(result);
        if (result.roundsExhausted()) {
            log.info("[ChatOrchestrator] 예산 소진 - 분류: {} ({})", classification.reason(), classification.detail());
        }

        return new ChatResult(responseText, result.images(), result.roundsExhausted(), classification, terminationReason);
    }

    private void saveIntermediateMessages(String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;

        List<AgentMessageEntity> entities = new ArrayList<>();
        for (ChatMessage msg : messages) {
            var builder = AgentMessageEntity.builder()
                    .sessionId(sessionId)
                    .role(msg.getRole());

            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                try {
                    builder.toolCalls(objectMapper.writeValueAsString(msg.getToolCalls()));
                } catch (JsonProcessingException e) {
                    log.warn("[ChatOrchestrator] toolCalls 직렬화 실패: {}", e.getMessage());
                }
            } else if ("assistant".equals(msg.getRole()) || "user".equals(msg.getRole())) {
                // 중간 텍스트 (assistant, toolCalls null) 또는 주입 사용자 메시지
                builder.content(msg.getContentAsString());
            } else if ("tool".equals(msg.getRole())) {
                builder.content(msg.getContentAsString())
                        .toolCallId(msg.getToolCallId());
            }

            entities.add(builder.build());
        }
        sessionManager.saveMessages(entities);
    }

    /**
     * 대화 결과 레코드
     */
    public record ChatResult(
            String response,
            List<byte[]> images,
            boolean roundsExhausted,
            ChatTerminationClassifier.Classification classification,
            ToolCallingService.TerminationReason terminationReason
    ) {
        /** 외부 취소로 중단되었는지 확인 */
        public boolean isCancelled() {
            return terminationReason == ToolCallingService.TerminationReason.CANCELLED;
        }
    }
}
