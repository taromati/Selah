package me.taromati.almah.llm.tool;

import java.util.*;

/**
 * 도구 실행 결과의 실패 여부를 판정하고 연속 실패 횟수를 추적한다.
 * 연속 실패 횟수가 임계치에 도달하면 해당 도구를 비활성화한다.
 */
public class ToolResultEvaluator {

    private final int consecutiveFailureLimit;
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    private final Set<String> disabledTools = new LinkedHashSet<>();

    public ToolResultEvaluator(int consecutiveFailureLimit) {
        this.consecutiveFailureLimit = consecutiveFailureLimit;
    }

    /**
     * 도구 실행 결과를 평가한다.
     *
     * @param toolName 도구 이름
     * @param result   도구 실행 결과
     * @return true이면 실패, false이면 성공
     */
    public boolean evaluate(String toolName, ToolResult result) {
        if (isFailed(result)) {
            int count = consecutiveFailures.merge(toolName, 1, Integer::sum);
            if (count >= consecutiveFailureLimit) {
                disabledTools.add(toolName);
            }
            return true;
        } else {
            consecutiveFailures.put(toolName, 0);
            return false;
        }
    }

    /**
     * 해당 도구가 비활성화 상태인지 반환한다.
     */
    public boolean isDisabled(String toolName) {
        return disabledTools.contains(toolName);
    }

    /**
     * 현재 비활성화된 도구 목록을 반환한다.
     */
    public Set<String> getDisabledTools() {
        return Collections.unmodifiableSet(disabledTools);
    }

    /**
     * 새 세션 시작 시 모든 상태를 초기화한다.
     */
    public void reset() {
        consecutiveFailures.clear();
        disabledTools.clear();
    }

    // ── 실패 판정 ──────────────────────────────────────────────────────────────

    private boolean isFailed(ToolResult result) {
        if (result.isFailure()) {
            return true;
        }
        String text = result.getText();
        if (text == null || text.isEmpty()) {
            return true;
        }
        if (text.equals("검색 결과 없음") || text.equals("검색 결과가 없습니다")) {
            return true;
        }
        if (text.startsWith("⛔") || text.startsWith("차단")) {
            return true;
        }
        if (text.startsWith("오류:") || text.startsWith("에러:") || text.startsWith("Error:")) {
            return true;
        }
        return false;
    }
}
