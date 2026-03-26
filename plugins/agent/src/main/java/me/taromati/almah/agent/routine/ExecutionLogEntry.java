package me.taromati.almah.agent.routine;

import java.time.Instant;
import java.util.List;

/**
 * 단일 실행 주기의 요약을 표현하는 값 객체.
 *
 * @param cycle         실행 주기 번호
 * @param timestamp     실행 시각
 * @param toolCalls     도구 호출 요약 목록
 * @param llmConclusion LLM의 마지막 텍스트 응답
 * @param status        실행 상태 ("COMPLETED", "PARTIAL", "FAIL", "ESCALATED")
 */
public record ExecutionLogEntry(
        int cycle,
        Instant timestamp,
        List<ToolCallSummary> toolCalls,
        String llmConclusion,
        String status
) {
    /**
     * 도구 호출 요약.
     *
     * @param tool          도구 이름
     * @param resultSummary 실행 결과 요약 (최대 200자)
     */
    public record ToolCallSummary(
            String tool,
            String resultSummary
    ) {}
}
