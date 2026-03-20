package me.taromati.almah.agent.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.MemoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 대화 메시지를 Memory 모듈에 인제스트하는 리스너.
 * TX 커밋 후 비동기로 실행되어 대화 흐름을 차단하지 않음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class AgentMemoryIngestionListener {

    private final MemoryService memoryService;

    @Async("memorySlowPathExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSaved(AgentMessageSavedEvent event) {
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("messageId", event.getMessageId());
            metadata.put("sessionId", event.getSessionId());
            metadata.put("role", event.getRole());
            metadata.put("source", "agent_chat");

            memoryService.ingest(event.getContent(), metadata);
            log.debug("[AgentMemoryIngestion] Ingested {} message: {}", event.getRole(), event.getMessageId());
        } catch (Exception e) {
            log.warn("[AgentMemoryIngestion] Failed to ingest message {}: {}", event.getMessageId(), e.getMessage());
        }
    }
}
