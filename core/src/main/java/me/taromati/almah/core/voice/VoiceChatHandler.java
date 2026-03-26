package me.taromati.almah.core.voice;

import java.util.function.Consumer;

/**
 * 음성 채팅 처리 인터페이스
 * core 모듈에 선언, aichat/agent 플러그인에서 구현 (역방향 의존성 해결)
 */
@FunctionalInterface
public interface VoiceChatHandler {
    VoiceChatResult processVoiceInput(String text, String userId);

    /**
     * 문장 단위 스트리밍 처리 — LLM 응답을 문장 단위로 분할하여 콜백으로 전달.
     * default: processVoiceInput() 호출 후 전체 텍스트를 단일 콜백으로 전달.
     */
    default VoiceChatResult processVoiceInputStreaming(String text, String userId, Consumer<String> sentenceCallback) {
        VoiceChatResult result = processVoiceInput(text, userId);
        if (result != null && result.text() != null && !result.text().isBlank()) {
            sentenceCallback.accept(result.text());
        }
        return result;
    }
}
