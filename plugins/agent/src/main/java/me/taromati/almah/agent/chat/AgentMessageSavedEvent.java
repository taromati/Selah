package me.taromati.almah.agent.chat;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Agent 메시지 저장 후 발행되는 이벤트.
 * Memory 모듈로 대화 내용을 인제스트하기 위한 이벤트.
 */
@Getter
@RequiredArgsConstructor
public class AgentMessageSavedEvent {
    private final String messageId;
    private final String sessionId;
    private final String role;
    private final String content;
}
