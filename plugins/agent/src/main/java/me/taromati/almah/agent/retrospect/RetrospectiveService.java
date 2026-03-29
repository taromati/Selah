package me.taromati.almah.agent.retrospect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import me.taromati.almah.agent.db.repository.AgentMessageRepository;
import me.taromati.almah.agent.service.PersistentContextReader;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.memory.MemoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class RetrospectiveService {

    private final ObjectMapper objectMapper;

    private final AgentConfigProperties config;
    private final LlmClientResolver clientResolver;
    private final PersistentContextReader persistentContextReader;
    private final AgentMessageRepository messageRepository;
    private final ObjectProvider<MemoryService> memoryServiceProvider;

    private final ReentrantLock mdWriteLock = new ReentrantLock();

    public RetrospectiveService(AgentConfigProperties config,
                                LlmClientResolver clientResolver,
                                PersistentContextReader persistentContextReader,
                                AgentMessageRepository messageRepository,
                                ObjectProvider<MemoryService> memoryServiceProvider,
                                ObjectMapper objectMapper) {
        this.config = config;
        this.clientResolver = clientResolver;
        this.persistentContextReader = persistentContextReader;
        this.messageRepository = messageRepository;
        this.memoryServiceProvider = memoryServiceProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 세션 회고: 아카이브된 세션의 대화를 분석하여 USER.md 갱신 + memory에 observations ingest.
     */
    public void retrospectSession(String sessionId) {
        if (!Boolean.TRUE.equals(config.getRetrospect().getEnabled())) return;

        List<AgentMessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        List<AgentMessageEntity> conversationMessages = messages.stream()
                .filter(m -> ("user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                        && m.getToolCalls() == null && m.getContent() != null)
                .toList();

        runRetrospective(sessionId, conversationMessages);
    }

    /**
     * 회고 핵심 로직: 대화를 분석하여 observations를 memory에 저장하고, 사용자 이해가 바뀌었으면 USER.md를 재작성한다.
     *
     * <p>LLM 응답 스키마:
     * <pre>
     * {
     *   "observations": ["사실 형태 관찰"],
     *   "userUnderstanding": "재작성된 서술형 프로필 또는 null",
     *   "operationalInsights": ["운영 패턴"],
     *   "skipReason": "학습할 것 없으면 이유, 있으면 null"
     * }
     * </pre>
     */
    public void runRetrospective(String sessionId, List<AgentMessageEntity> messages) {
        if (messages.size() < config.getRetrospect().getMinMessagesForSession()) {
            log.debug("[Retrospect] 메시지 부족 ({}개), 스킵", messages.size());
            return;
        }

        StringBuilder conversation = new StringBuilder();
        for (var msg : messages) {
            conversation.append(msg.getRole()).append(": ")
                    .append(truncate(msg.getContent(), 500)).append("\n");
        }

        try {
            LlmClient client = clientResolver.resolve(config.getLlmProviderName());

            String existingUserMd = persistentContextReader.readUserMd();
            String userMdContext = (existingUserMd != null && !existingUserMd.isBlank())
                    ? "현재 USER.md:\n---\n" + existingUserMd + "\n---\n\n"
                    : "현재 USER.md: (비어있음)\n\n";

            String memoryObservationsContext = fetchRecentObservations();

            String systemPrompt = """
                    당신은 대화 분석 전문가입니다. 대화를 분석하여 사용자에 대한 이해를 갱신하세요.

                    %s%s\
                    분석 후 JSON으로 응답하세요:
                    1. observations: 이번 대화에서 발견한 사용자에 대한 사실 관찰 배열 ("~이다", "~한다" 형태)
                    2. userUnderstanding: 사용자에 대한 이해가 바뀌었으면 USER.md 전체를 서술형으로 재작성. 변화 없으면 null
                    3. operationalInsights: 효과적이었던 도구 사용 패턴, 개선점 (배열)
                    4. skipReason: 학습할 것이 없으면 이유 문자열, 있으면 null

                    userUnderstanding 작성 규칙:
                    - 기존 USER.md의 정보 중 명시적으로 부정되지 않은 내용은 유지
                    - 최근 관찰을 반영하고, 오래된 관심사는 비중을 자연 축소
                    - bullet 나열이 아닌 서술형으로 작성
                    - 변화가 없으면 반드시 null을 반환 (불필요한 재작성 금지)

                    JSON만 출력, 마크다운 코드블록 없이:
                    {"observations":[],"userUnderstanding":null,"operationalInsights":[],"skipReason":null}""".formatted(userMdContext, memoryObservationsContext);

            List<ChatMessage> context = List.of(
                    ChatMessage.builder().role("system").content(systemPrompt).build(),
                    ChatMessage.builder().role("user").content(conversation.toString()).build()
            );

            var response = client.chatCompletion(context, SamplingParams.withTemperature(0.3));
            String content = response.getContent();
            if (content == null || content.isBlank()) return;

            JsonNode json = parseJson(content);
            if (json == null) return;

            // observations ingest
            List<String> observations = extractStringArray(json, "observations");
            ingestObservations(observations, sessionId);

            // operationalInsights ingest
            List<String> operationalInsights = extractStringArray(json, "operationalInsights");
            ingestOperationalInsights(operationalInsights, sessionId);

            // userUnderstanding → writeUserMd (전체 교체)
            String userUnderstanding = json.has("userUnderstanding") && !json.get("userUnderstanding").isNull()
                    ? json.get("userUnderstanding").asText() : null;

            if (userUnderstanding != null) {
                mdWriteLock.lock();
                try {
                    persistentContextReader.writeUserMd(userUnderstanding);
                } finally {
                    mdWriteLock.unlock();
                }
            }

            String skipReason = json.has("skipReason") && !json.get("skipReason").isNull()
                    ? json.get("skipReason").asText() : null;

            log.info("[Retrospect] 세션 {} 회고 완료: observations={}, understanding={}, operational={}, skip={}",
                    sessionId, observations.size(), userUnderstanding != null, operationalInsights.size(), skipReason);

        } catch (Exception e) {
            log.warn("[Retrospect] 세션 {} 회고 실패: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 마이그레이션: 기존 bullet 형식 USER.md를 서술형으로 변환하고, 각 bullet을 memory에 이관한다.
     */
    public void migrateUserMd() {
        String existingUserMd = persistentContextReader.readUserMd();
        if (existingUserMd == null || existingUserMd.isBlank()) return;

        // bullet 파싱: "- "로 시작하는 줄만 관찰로 추출, 헤더(##/###) 제외
        List<String> bulletObservations = new ArrayList<>();
        for (String line : existingUserMd.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ")) {
                bulletObservations.add(trimmed.substring(2));
            }
        }

        // 각 관찰을 memory에 이관
        MemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService != null && !bulletObservations.isEmpty()) {
            for (String obs : bulletObservations) {
                Map<String, String> metadata = new HashMap<>();
                metadata.put("source", "migration");
                metadata.put("type", "user_observation");
                memoryService.ingest(obs, metadata);
            }
        }

        // LLM으로 서술형 변환
        try {
            LlmClient client = clientResolver.resolve(config.getLlmProviderName());

            String systemPrompt = """
                    아래 사용자 프로필(bullet 형식)을 자연스러운 서술형으로 변환하세요.
                    기존 정보를 모두 포함하되, 핵심 정보를 중심으로 자연스럽게 서술하세요.
                    서술형 텍스트만 반환하세요 (마크다운 코드블록 없이).""";

            List<ChatMessage> context = List.of(
                    ChatMessage.builder().role("system").content(systemPrompt).build(),
                    ChatMessage.builder().role("user").content(existingUserMd).build()
            );

            var response = client.chatCompletion(context, SamplingParams.withTemperature(0.3));
            String narrative = response.getContent();
            if (narrative != null && !narrative.isBlank()) {
                persistentContextReader.writeUserMd(narrative);
                log.info("[Retrospect] USER.md 마이그레이션 완료: {}줄 bullet → 서술형", bulletObservations.size());
            }
        } catch (Exception e) {
            log.warn("[Retrospect] USER.md 마이그레이션 실패: {}", e.getMessage());
        }
    }

    /**
     * Task 회고: 완료/실패한 Task의 실행 패턴을 분석하여 memory 모듈에 인사이트 ingest.
     */
    public void retrospectTask(String taskTitle, String description, String progress,
                                boolean success, String auditLogSummary) {
        if (!Boolean.TRUE.equals(config.getRetrospect().getTaskEnabled())) return;

        try {
            LlmClient client = clientResolver.resolve(config.getLlmProviderName());

            StringBuilder taskInfo = new StringBuilder();
            taskInfo.append("제목: ").append(taskTitle).append("\n");
            if (description != null) taskInfo.append("설명: ").append(description).append("\n");
            taskInfo.append("결과: ").append(success ? "성공" : "실패").append("\n");
            if (progress != null) taskInfo.append("진행 상황: ").append(truncate(progress, 500)).append("\n");
            if (auditLogSummary != null) taskInfo.append("실행 이력: ").append(truncate(auditLogSummary, 500)).append("\n");

            String systemPrompt = """
                    당신은 작업 실행 분석 전문가입니다. 아래 Task 실행 결과를 분석하여 JSON으로 응답하세요.

                    분석 대상:
                    1. insights: 학습한 패턴, 효과적/비효과적이었던 접근, 개선점 (배열)
                    2. skipReason: 학습할 것이 없으면 이유 문자열, 있으면 null

                    규칙:
                    - 단순 실행/성공은 skipReason에 이유를 적고 insights를 빈 배열로
                    - 재사용 가능한 운영 패턴만 추출
                    - 각 인사이트를 한 줄로 작성. 행동 규칙 형태 (~해라/~하지 마라)
                    - 이유는 대시(—) 뒤에 간결하게
                    - 장황한 분석적 서술 금지. 짧고 실행 가능한 규칙만.
                    - JSON만 출력, 마크다운 코드블록 없이

                    {"insights":[],"skipReason":null}""";

            List<ChatMessage> context = List.of(
                    ChatMessage.builder().role("system").content(systemPrompt).build(),
                    ChatMessage.builder().role("user").content(taskInfo.toString()).build()
            );

            var response = client.chatCompletion(context, SamplingParams.withTemperature(0.3));
            String content = response.getContent();
            if (content == null || content.isBlank()) return;

            JsonNode json = parseJson(content);
            if (json == null) return;

            String skipReason = json.has("skipReason") && !json.get("skipReason").isNull()
                    ? json.get("skipReason").asText() : null;
            if (skipReason != null) {
                log.debug("[Retrospect] Task '{}' 스킵: {}", taskTitle, skipReason);
                return;
            }

            List<String> insights = extractStringArray(json, "insights");
            if (insights.isEmpty()) return;

            ingestOperationalInsights(insights, null);

            log.info("[Retrospect] Task '{}' 회고 완료: insights={}", taskTitle, insights.size());

        } catch (Exception e) {
            log.warn("[Retrospect] Task '{}' 회고 실패: {}", taskTitle, e.getMessage());
        }
    }

    // ── Memory ingest ───────────────────────

    private void ingestObservations(List<String> observations, String sessionId) {
        if (observations.isEmpty()) return;
        MemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService == null) {
            log.warn("[Retrospect] MemoryService 비활성 — observations {}건 저장 불가", observations.size());
            return;
        }
        for (String obs : observations) {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("source", "retrospect");
            metadata.put("type", "user_observation");
            if (sessionId != null) metadata.put("sessionId", sessionId);
            memoryService.ingest(obs, metadata);
        }
    }

    private void ingestOperationalInsights(List<String> insights, String sessionId) {
        if (insights.isEmpty()) return;
        MemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService == null) {
            log.warn("[Retrospect] MemoryService 비활성 — insights {}건 저장 불가", insights.size());
            return;
        }
        for (String insight : insights) {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("source", "retrospect");
            metadata.put("type", "operational_insight");
            if (sessionId != null) metadata.put("sessionId", sessionId);
            memoryService.ingest(insight, metadata);
        }
    }

    private String fetchRecentObservations() {
        MemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService == null) return "";
        try {
            var results = memoryService.search("사용자 관찰 최근 선호 패턴");
            if (results.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("최근 사용자 관찰:\n");
            int limit = Math.min(results.size(), 20);
            for (int i = 0; i < limit; i++) {
                sb.append("- ").append(results.get(i).content()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Retrospect] Memory 검색 실패: {}", e.getMessage());
            return "";
        }
    }

    // ── 공통 유틸리티 ───────────────────────

    private JsonNode parseJson(String content) {
        try {
            String cleaned = content.strip();
            if (cleaned.startsWith("```")) {
                int start = cleaned.indexOf('\n') + 1;
                int end = cleaned.lastIndexOf("```");
                if (start > 0 && end > start) {
                    cleaned = cleaned.substring(start, end).strip();
                }
            }
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[Retrospect] JSON 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private List<String> extractStringArray(JsonNode json, String fieldName) {
        List<String> result = new ArrayList<>();
        if (json.has(fieldName) && json.get(fieldName).isArray()) {
            for (JsonNode item : json.get(fieldName)) {
                String text = item.asText();
                if (text != null && !text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
