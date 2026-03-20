package me.taromati.almah.llm.util;

import me.taromati.almah.llm.client.dto.ChatCompletionResponse;

import java.util.List;
import java.util.regex.Pattern;

/**
 * LLM 프로바이더 공통 아티팩트를 응답 content에서 제거하는 유틸리티.
 * 새 모델/프로바이더의 아티팩트 발견 시 Pattern 상수 추가 + ARTIFACT_PATTERNS 리스트에 등록.
 */
public final class LlmResponseSanitizer {

    // 블록 전체 제거 — 닫힌 블록 + 미닫힘(끝까지)
    private static final Pattern THINK_BLOCK =
            Pattern.compile("<think>[\\s\\S]*?</think>\\s*|<think>[\\s\\S]*$");
    // vLLM enable_thinking 모드: <think>가 reasoning_content 필드로 분리되어
    // content에 "thinking_text</think>실제응답" 형태로 남는 경우 — 선두~</think> 제거
    private static final Pattern ORPHAN_THINK_CLOSE =
            Pattern.compile("^[\\s\\S]*?</think>\\s*");
    private static final Pattern TOOL_CALL_BLOCK =
            Pattern.compile("<tool_call>[\\s\\S]*?</tool_call>\\s*|<tool_call>[\\s\\S]*$");

    // 태그 없는 thinking: 영어 분석이 content에 직접 출력된 경우.
    // "Thinking Process:", "Let me analyze" 등으로 시작하는 다중 라인 영어 텍스트를
    // 한국어/JSON 실제 콘텐츠 직전까지 제거. 개행이 있어야 다중 라인 분석으로 확인.
    private static final Pattern UNTAGGED_THINKING_PREFIX =
            Pattern.compile("^(?:Thinking Process|(?:Let me|I need to|I will|I should|I'll|First,?|Wait,?|Now,?|OK,?|Alright,?)\\s)[^\\n]*\\n[\\s\\S]*?(?=[가-힣\\{\\[])");

    // 화살표 메타 코멘터리 — 모델이 자기 출력을 평가하는 내부 독백 누출.
    // e.g., ..." -> 이건 좀 긴데, 단순화해서 전달해야겠네요
    private static final Pattern ARROW_SELF_REFLECTION =
            Pattern.compile("(?:\"\\s*->|\\n\\s*->)\\s+[가-힣][^\\n]*");

    private static final List<Pattern> ARTIFACT_PATTERNS = List.of(
            THINK_BLOCK,
            ORPHAN_THINK_CLOSE,
            TOOL_CALL_BLOCK,
            UNTAGGED_THINKING_PREFIX,
            ARROW_SELF_REFLECTION
    );

    private LlmResponseSanitizer() {}

    public static String sanitize(String text) {
        if (text == null) return null;
        String result = text;
        for (Pattern pattern : ARTIFACT_PATTERNS) {
            result = pattern.matcher(result).replaceAll("");
        }
        return result.trim();
    }

    public static void sanitize(ChatCompletionResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) return;
        var message = response.getChoices().getFirst().getMessage();
        if (message != null && message.getContent() != null) {
            message.setContent(sanitize(message.getContent()));
        }
    }
}
