package me.taromati.almah.agent.chat;

import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.tool.ToolCallingService.TerminationReason;
import me.taromati.almah.llm.tool.ToolCallingService.ToolCallingResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ToolCallingResult의 intermediateMessages를 분석하여
 * agent 수준의 종료 분류를 수행합니다.
 *
 * <ul>
 *   <li>{@link ChatExitReason#COMPLETED} — 정상 완료</li>
 *   <li>{@link ChatExitReason#CANCELLED} — 사용자 취소</li>
 *   <li>{@link ChatExitReason#TOOL_FAILURE} — 도구 인프라 장애 (MCP 타임아웃, 연결 불가)</li>
 *   <li>{@link ChatExitReason#PROGRESS_STALLED} — 동일 도구+파라미터 반복 실패</li>
 *   <li>{@link ChatExitReason#WORK_INCOMPLETE} — 정상적 시간 부족</li>
 * </ul>
 */
public final class ChatTerminationClassifier {

    private static final int STALL_THRESHOLD = 3;

    private static final Pattern TIMEOUT_PATTERN = Pattern.compile(
            "(?i)(timeout|timed? ?out|connection refused|connect(ion)? failed|EHOSTUNREACH|ECONNREFUSED|연결.{0,5}실패|타임아웃)");
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?i)(error|exception|에러|오류|실패|failed)");

    private ChatTerminationClassifier() {}

    /**
     * Agent 수준 종료 분류.
     */
    public enum ChatExitReason {
        COMPLETED,
        CANCELLED,
        TOOL_FAILURE,
        PROGRESS_STALLED,
        WORK_INCOMPLETE
    }

    /**
     * 분류 결과.
     */
    public record Classification(
            ChatExitReason reason,
            /** HANDOFF 생성 시 권장 maxRetries */
            int suggestedMaxRetries,
            /** 분류 근거 요약 (로깅/디버그용) */
            String detail
    ) {}

    /**
     * ToolCallingResult를 분석하여 종료 분류를 반환합니다.
     */
    public static Classification classify(ToolCallingResult result) {
        TerminationReason termination = result.terminationReason();
        if (termination == null) termination = TerminationReason.COMPLETED;

        // 1. 정상 완료
        if (termination == TerminationReason.COMPLETED) {
            return new Classification(ChatExitReason.COMPLETED, 0, "정상 완료");
        }

        // 2. 취소
        if (termination == TerminationReason.CANCELLED) {
            return new Classification(ChatExitReason.CANCELLED, 0, "사용자 취소");
        }

        // 3. intermediate messages 분석
        List<ChatMessage> messages = result.intermediateMessages();
        if (messages == null || messages.isEmpty()) {
            return new Classification(ChatExitReason.WORK_INCOMPLETE, 5, "도구 호출 없이 시간 초과");
        }

        // 도구 결과에서 인프라 장애 패턴 탐색
        int toolFailureCount = 0;
        int totalToolCalls = 0;
        Map<String, Integer> callSignatureCount = new HashMap<>();

        for (ChatMessage msg : messages) {
            if ("tool".equals(msg.getRole())) {
                totalToolCalls++;
                String content = msg.getContentAsString();
                if (content != null && TIMEOUT_PATTERN.matcher(content).find()) {
                    toolFailureCount++;
                }
            }
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null) {
                        String sig = tc.getFunction().getName() + ":" + normalizeArgs(tc.getFunction().getArguments());
                        callSignatureCount.merge(sig, 1, Integer::sum);
                    }
                }
            }
        }

        // 4. TOOL_FAILURE: 50% 이상 도구 호출이 인프라 장애
        if (totalToolCalls > 0 && toolFailureCount * 2 >= totalToolCalls) {
            return new Classification(ChatExitReason.TOOL_FAILURE, 2,
                    "도구 인프라 장애 (" + toolFailureCount + "/" + totalToolCalls + " 실패)");
        }

        // 5. PROGRESS_STALLED: 동일 시그니처 3회 이상 반복
        for (var entry : callSignatureCount.entrySet()) {
            if (entry.getValue() >= STALL_THRESHOLD) {
                String toolName = entry.getKey().split(":")[0];
                return new Classification(ChatExitReason.PROGRESS_STALLED, 2,
                        "동일 도구 반복 (" + toolName + " " + entry.getValue() + "회)");
            }
        }

        // 6. 에러 비율이 높으면 PROGRESS_STALLED (도구 결과의 60% 이상 에러)
        if (totalToolCalls >= 3) {
            int errorCount = 0;
            for (ChatMessage msg : messages) {
                if ("tool".equals(msg.getRole())) {
                    String content = msg.getContentAsString();
                    if (content != null && ERROR_PATTERN.matcher(content).find()) {
                        errorCount++;
                    }
                }
            }
            if (errorCount * 5 >= totalToolCalls * 3) { // 60%
                return new Classification(ChatExitReason.PROGRESS_STALLED, 2,
                        "높은 에러 비율 (" + errorCount + "/" + totalToolCalls + ")");
            }
        }

        // 7. 기본: 정상적 시간 부족
        return new Classification(ChatExitReason.WORK_INCOMPLETE, 5,
                "시간 부족 (도구 " + totalToolCalls + "회 실행)");
    }

    /**
     * 인자를 정규화하여 동일 호출 판별 (공백/순서 무시).
     */
    private static String normalizeArgs(String args) {
        if (args == null) return "";
        // 간단한 정규화: 공백 제거 후 해시
        return args.replaceAll("\\s+", "").trim();
    }
}
