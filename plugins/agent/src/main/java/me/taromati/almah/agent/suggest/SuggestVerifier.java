package me.taromati.almah.agent.suggest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 제안된 ACTION 블록의 품질을 검증한다.
 * 별도 LLM 호출 1회로 중복 + 실행가능성을 판정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SuggestVerifier {

    private final LlmClientResolver clientResolver;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;

    public record SuggestCandidate(String title, String description) {}

    public record VerificationContext(
            List<String> activeTitles,
            List<String> completedTitles,
            List<String> recentSuggestions
    ) {}

    public record VerificationResult(String title, Verdict verdict, String reason, String description) {
        public VerificationResult(String title, Verdict verdict, String reason) {
            this(title, verdict, reason, null);
        }
    }

    public enum Verdict {
        PASS, REJECT_DUPLICATE, REJECT_INFEASIBLE, REJECT_LOW_VALUE
    }

    /**
     * 제안 후보 목록을 검증한다.
     * @return 각 후보에 대한 판정 결과 (입력 순서 유지)
     */
    public List<VerificationResult> verify(List<SuggestCandidate> candidates, VerificationContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        String providerName = config.getSuggestProvider();
        LlmClient client = clientResolver.resolve(providerName);

        String prompt = buildVerificationPrompt(candidates, ctx);

        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role("system").content("당신은 제안 품질 검증 시스템입니다. JSON으로만 응답하세요.").build(),
                ChatMessage.builder().role("user").content(prompt).build()
        );

        SamplingParams params = new SamplingParams(
                1024, 0.3, null, null, null, null, null);

        var response = client.chatCompletion(messages, params);
        String text = response.getContent();

        return parseVerificationResponse(text, candidates);
    }

    private String buildVerificationPrompt(List<SuggestCandidate> candidates, VerificationContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 제안을 검증하세요. 각 제안에 대해 JSON으로 판정하세요.\n\n");

        sb.append("## 제안 목록\n");
        for (int i = 0; i < candidates.size(); i++) {
            var c = candidates.get(i);
            sb.append(i + 1).append(". ").append(c.title());
            if (c.description() != null) {
                sb.append("\n   ").append(c.description());
            }
            sb.append("\n");
        }

        if (ctx.activeTitles() != null && !ctx.activeTitles().isEmpty()) {
            sb.append("\n## 현재 활성 Task\n");
            ctx.activeTitles().forEach(t -> sb.append("- ").append(t).append("\n"));
        }

        if (ctx.completedTitles() != null && !ctx.completedTitles().isEmpty()) {
            sb.append("\n## 최근 완료 Task\n");
            ctx.completedTitles().forEach(t -> sb.append("- ").append(t).append("\n"));
        }

        if (ctx.recentSuggestions() != null && !ctx.recentSuggestions().isEmpty()) {
            sb.append("\n## 최근 제안 이력\n");
            ctx.recentSuggestions().forEach(s -> sb.append("- ").append(s).append("\n"));
        }

        sb.append("\n## 판정 기준\n");
        sb.append("- PASS: 실행 가능하고, 중복이 아님\n");
        sb.append("- REJECT_DUPLICATE: 활성/완료 Task 또는 최근 제안과 의미적 중복\n");
        sb.append("- REJECT_INFEASIBLE: 현재 도구/리소스로 실행 불가능\n");
        sb.append("- REJECT_LOW_VALUE: 실행해도 실질적 효과가 없거나, 단순 확인/조회 수준의 작업\n");
        sb.append("\n## 응답 형식 (JSON 배열만, 다른 텍스트 없이)\n");
        sb.append("[{\"title\": \"...\", \"verdict\": \"PASS|REJECT_DUPLICATE|REJECT_INFEASIBLE|REJECT_LOW_VALUE\", \"reason\": \"...\"}]\n");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<VerificationResult> parseVerificationResponse(String text, List<SuggestCandidate> candidates) {
        if (text == null || text.isBlank()) {
            // 파싱 실패 → 전체 PASS 폴백
            return candidates.stream()
                    .map(c -> new VerificationResult(c.title(), Verdict.PASS, "검증 응답 비어있음", c.description()))
                    .toList();
        }

        try {
            // JSON 배열 추출 (응답에 다른 텍스트가 섞여있을 수 있음)
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start < 0 || end < 0 || end <= start) {
                throw new RuntimeException("JSON 배열을 찾을 수 없음");
            }
            String json = text.substring(start, end + 1);

            List<Map<String, String>> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            List<VerificationResult> results = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                var item = parsed.get(i);
                String title = item.get("title");
                String verdictStr = item.get("verdict");
                String reason = item.get("reason");
                // description: LLM 응답에 없으면 원본 candidate에서 가져옴
                String description = i < candidates.size() ? candidates.get(i).description() : null;

                Verdict verdict;
                try {
                    verdict = Verdict.valueOf(verdictStr);
                } catch (Exception e) {
                    verdict = Verdict.PASS; // 알 수 없는 verdict → PASS 폴백
                }
                results.add(new VerificationResult(title, verdict, reason, description));
            }
            return results;

        } catch (Exception e) {
            throw new RuntimeException("검증 응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
