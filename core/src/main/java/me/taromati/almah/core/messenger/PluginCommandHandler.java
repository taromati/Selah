package me.taromati.almah.core.messenger;

/**
 * 플러그인 명령어 핸들러 인터페이스.
 * PluginListener 내부에서 명령어 라우팅을 분리할 때 사용합니다.
 * 각 도메인(도구, 루틴, LLM 인증 등)이 이를 구현하여 자동 등록됩니다.
 */
public interface PluginCommandHandler {

    /**
     * 주어진 명령어를 처리할 수 있는지 판별합니다.
     *
     * @param content 메시지 원문 (trim 완료)
     * @return 처리 가능하면 true
     */
    boolean canHandle(String content);

    /**
     * 명령어를 처리합니다.
     * 호출부의 executor에서 실행되므로 블로킹 가능합니다.
     *
     * @param channel 메신저 채널 참조
     * @param content 메시지 원문 (trim 완료)
     * @param channelId 채널 ID
     */
    void handle(ChannelRef channel, String content, String channelId);
}
