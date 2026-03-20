package me.taromati.almah.llm.tool;

import me.taromati.almah.llm.client.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tool 실행 시 대화 컨텍스트를 전달하기 위한 ThreadLocal 기반 홀더.
 * ToolHandler는 @FunctionalInterface라 시그니처 변경 불가 → ThreadLocal로 컨텍스트 전달.
 */
@Component
public class ToolExecutionContext {

    private final ThreadLocal<String> lastUserMessage = new ThreadLocal<>();
    private final ThreadLocal<List<ChatMessage>> recentMessages = new ThreadLocal<>();

    public void setLastUserMessage(String message) {
        lastUserMessage.set(message);
    }

    public String getLastUserMessage() {
        return lastUserMessage.get();
    }

    public void setRecentMessages(List<ChatMessage> messages) {
        recentMessages.set(messages);
    }

    public List<ChatMessage> getRecentMessages() {
        return recentMessages.get();
    }

    public void clear() {
        lastUserMessage.remove();
        recentMessages.remove();
    }
}
