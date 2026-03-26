package me.taromati.almah.agent.routine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 응답에서 [SELF_EVAL: PASS|PARTIAL|FAIL] 블록을 파싱하는 유틸리티.
 * ChatTerminationClassifier와 동일 패턴 — static 메서드, 상태 없음.
 */
public class SelfEvalParser {

    private static final Pattern SELF_EVAL_PATTERN =
            Pattern.compile("\\[SELF_EVAL:\\s*(PASS|PARTIAL|FAIL)](?:\\s*(.*))?");

    /**
     * 응답 전체를 스캔하여 마지막 [SELF_EVAL: ...] 블록을 파싱한다.
     * 매칭 없음 → ABSENT.
     */
    public static SelfEvalResult parse(String response) {
        if (response == null || response.isBlank()) {
            return new SelfEvalResult(SelfEvalStatus.ABSENT, null);
        }

        SelfEvalStatus lastStatus = null;
        String lastReason = null;

        Matcher matcher = SELF_EVAL_PATTERN.matcher(response);
        while (matcher.find()) {
            String statusStr = matcher.group(1);
            String reason = matcher.group(2);

            lastStatus = switch (statusStr) {
                case "PASS" -> SelfEvalStatus.PASS;
                case "PARTIAL" -> SelfEvalStatus.PARTIAL;
                case "FAIL" -> SelfEvalStatus.FAIL;
                default -> null;
            };
            lastReason = reason != null ? reason.trim() : null;
            if (lastReason != null && lastReason.isEmpty()) lastReason = null;
        }

        if (lastStatus == null) {
            return new SelfEvalResult(SelfEvalStatus.ABSENT, null);
        }
        return new SelfEvalResult(lastStatus, lastReason);
    }

    public record SelfEvalResult(SelfEvalStatus status, String reason) {}

    public enum SelfEvalStatus {
        PASS, PARTIAL, FAIL, ABSENT
    }
}
