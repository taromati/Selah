package me.taromati.almah.agent.suggest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.embedding.EmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class TopicSelector {

    private final LlmClientResolver clientResolver;
    private final AgentConfigProperties config;
    private final SuggestHistory suggestHistory;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TopicSelector(LlmClientResolver clientResolver,
                          AgentConfigProperties config,
                          SuggestHistory suggestHistory,
                          @Nullable EmbeddingService embeddingService) {
        this.clientResolver = clientResolver;
        this.config = config;
        this.suggestHistory = suggestHistory;
        this.embeddingService = embeddingService;
    }

    /**
     * 자극에서 주제 후보 3개를 생성한다.
     */
    public List<TopicCandidate> selectTopics(StimulusResult stimulus) {
        try {
            String blacklist = buildCuriosityBlacklist();
            String prompt = buildTopicSelectionPrompt(stimulus, blacklist);

            LlmClient client = clientResolver.resolve(config.getSuggestProvider());
            SamplingParams params = new SamplingParams(
                    512, 0.9, null, null, null, null, null);

            var response = client.chatCompletion(List.of(
                    ChatMessage.builder().role("system")
                            .content("주제 선택 엔진입니다. JSON 배열로만 응답하세요.").build(),
                    ChatMessage.builder().role("user").content(prompt).build()
            ), params);

            return parseTopicCandidates(response.getContent());
        } catch (Exception e) {
            log.warn("[TopicSelector] 주제 선택 실패: {}", e.getMessage());
            return List.of();
        }
    }

    String buildTopicSelectionPrompt(StimulusResult stimulus, String blacklist) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 정보를 기반으로 사용자에게 제안할 작업 주제 3개를 선택하세요.\n\n");

        sb.append("## 수집된 정보\n");
        sb.append(stimulus.context()).append("\n\n");

        var recentSuggestions = suggestHistory.getRecent();
        if (!recentSuggestions.isEmpty()) {
            sb.append("## 최근 제안 이력 (중복 금지)\n");
            int limit = Math.min(recentSuggestions.size(), 10);
            for (int i = 0; i < limit; i++) {
                var s = recentSuggestions.get(i);
                sb.append("- [").append(s.getResponse() != null ? s.getResponse() : "미응답").append("] ")
                        .append(StringUtils.truncate(s.getContent(), 80)).append("\n");
            }
            sb.append("\n");
        }

        if (blacklist != null && !blacklist.isBlank()) {
            sb.append("## 유사 주제 블랙리스트 (이 주제는 피하세요)\n");
            sb.append(blacklist).append("\n\n");
        }

        sb.append("## 응답 형식 (JSON 배열만)\n");
        sb.append("[{\"topic\": \"주제\", \"rationale\": \"선택 이유\"}]\n");
        sb.append("서로 다른 영역에서 3개를 고르세요. 최근 제안과 겹치지 않아야 합니다.\n");

        return sb.toString();
    }

    String buildCuriosityBlacklist() {
        var recent = suggestHistory.getRecent();
        if (recent.isEmpty()) return "";

        List<String> recentTitles = recent.stream()
                .map(s -> StringUtils.truncate(s.getContent(), 80))
                .limit(10)
                .toList();

        if (embeddingService == null) {
            return recentTitles.stream()
                    .collect(Collectors.joining("\n- ", "- ", ""));
        }

        try {
            embeddingService.embedBatch(recentTitles);
            return recentTitles.stream()
                    .collect(Collectors.joining("\n- ", "- ", ""));
        } catch (Exception e) {
            log.warn("[TopicSelector] 임베딩 블랙리스트 생성 실패: {}", e.getMessage());
            return recentTitles.stream()
                    .collect(Collectors.joining("\n- ", "- ", ""));
        }
    }

    @SuppressWarnings("unchecked")
    List<TopicCandidate> parseTopicCandidates(String text) {
        if (text == null || text.isBlank()) return List.of();

        try {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start < 0 || end < 0 || end <= start) return List.of();
            String json = text.substring(start, end + 1);

            List<Map<String, String>> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            List<TopicCandidate> candidates = new ArrayList<>();
            for (var item : parsed) {
                String topic = item.get("topic");
                String rationale = item.get("rationale");
                if (topic != null && !topic.isBlank()) {
                    candidates.add(new TopicCandidate(topic, rationale));
                }
            }
            return candidates;
        } catch (Exception e) {
            log.warn("[TopicSelector] 주제 후보 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }
}
