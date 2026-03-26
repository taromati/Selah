package me.taromati.almah.core.voice;

/**
 * 음성 세션 관리 인터페이스
 * core 모듈에 선언, voice 모듈(VoiceChatManager)에서 구현.
 * Agent 도구에서 프로그래밍적으로 음성 세션을 시작/종료할 때 사용.
 */
public interface VoiceSessionManager {

    boolean canStart();

    boolean startSessionByName(String voiceChannelName, String textChannelName, VoiceChatHandler handler);

    void stopAllSessions();

    boolean isAnySessionActive();
}
