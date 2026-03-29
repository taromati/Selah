package me.taromati.almah.llm.tool;

/**
 * SSE 스트리밍 콜백 인터페이스.
 * Consumer<String> 해킹을 타입 안전한 메서드로 교체한다.
 * ToolCallingService가 이 인터페이스를 호출한다. null이면 비스트리밍 경로.
 */
public interface StreamingListener {
    void onToken(String token);
    void onFlush();
    void onToolStart(String name, String argsPreview);
    void onToolDone(String name, String argsPreview, String resultSummary);
}
