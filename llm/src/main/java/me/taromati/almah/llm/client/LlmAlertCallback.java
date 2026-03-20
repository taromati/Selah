package me.taromati.almah.llm.client;

/**
 * LLM 장애 알림 콜백 인터페이스
 * AlertService 등이 구현하여 OpenAiClient에 주입
 */
@FunctionalInterface
public interface LlmAlertCallback {

    /**
     * 크리티컬 장애 알림 전송
     * @param alertType 알림 타입 (스팸 방지용 키)
     * @param title 알림 제목
     * @param details 상세 내용
     */
    void sendCriticalAlert(String alertType, String title, String details);
}
