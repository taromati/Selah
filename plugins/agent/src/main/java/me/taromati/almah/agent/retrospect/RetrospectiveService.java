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
import java.util.LinkedHashMap;
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
     * 세션 회고: 아카이브된 세션의 대화를 분석하여 USER.md 갱신 + memory 모듈에 인사이트 ingest.
     */
    public void retrospectSession(String sessionId) {
        if (!Boolean.TRUE.equals(config.getRetrospect().getEnabled())) return;

        List<AgentMessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // user/assistant만 필터 (tool_calls/tool 제외)
        List<AgentMessageEntity> conversationMessages = messages.stream()
                .filter(m -> ("user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                        && m.getToolCalls() == null && m.getContent() != null)
                .toList();

        if (conversationMessages.size() < config.getRetrospect().getMinMessagesForSession()) {
            log.debug("[Retrospect] 세션 {} 메시지 부족 ({}개), 스킵",
                    sessionId, conversationMessages.size());
            return;
        }

        // 대화 내용 구성
        StringBuilder conversation = new StringBuilder();
        for (var msg : conversationMessages) {
            conversation.append(msg.getRole()).append(": ")
                    .append(truncate(msg.getContent(), 500)).append("\n");
        }

        try {
            LlmClient client = clientResolver.resolve(config.getLlmProviderName());

            // 기존 USER.md를 프롬프트에 포함 (섹션 인식용)
            String existingUserMd = persistentContextReader.readUserMd();
            String userMdContext = (existingUserMd != null && !existingUserMd.isBlank())
                    ? "현재 USER.md:\n---\n" + existingUserMd + "---\n\n"
                    : "현재 USER.md: (비어있음)\n\n";

            String systemPrompt = """
                    당신은 대화 분석 전문가입니다. 대화를 분석하여 사용자 프로필(USER.md)을 갱신하세요.

                    %s\
                    분석 후 JSON으로 응답하세요:
                    1. sectionUpdates: 갱신할 섹션 배열. 각 항목은 header(마크다운 헤더)와 content(본문)
                    2. operationalInsights: 효과적이었던 도구 사용 패턴, 개선점 (배열)
                    3. skipReason: 학습할 것이 없으면 이유 문자열, 있으면 null

                    섹션 갱신 규칙:
                    - 기존 섹션의 내용과 새 인사이트를 병합하여 중복을 제거하세요
                    - 유사한 항목은 하나로 통합하세요 (예: 같은 주제의 반복된 관찰)
                    - 변경이 필요한 섹션만 포함하세요 (변경 없는 섹션은 제외)
                    - 각 섹션의 전체 content를 반환하세요 (추가분만이 아닌 병합된 전체)
                    - header는 마크다운 헤더 형식 유지 (예: "## 자동 학습", "### 취미/일상")
                    - content에 header를 포함하지 마세요 (코드가 자동 추가)
                    - 새 카테고리가 필요하면 적절한 header 레벨(##/###)로 추가하세요
                    - 일상 대화, 단순 질문/답변은 skipReason 기재

                    operationalInsights 작성 규칙:
                    - 각 인사이트를 한 줄로 작성
                    - 행동 규칙 형태로 (~해라/~하지 마라)
                    - 이유는 대시(—) 뒤에 간결하게
                    - 장황한 분석적 서술 금지. 짧고 실행 가능한 규칙만.

                    JSON만 출력, 마크다운 코드블록 없이:
                    {"sectionUpdates":[{"header":"## 예시","content":"- 항목"}],"operationalInsights":[],"skipReason":null}""".formatted(userMdContext);

            List<ChatMessage> context = List.of(
                    ChatMessage.builder().role("system").content(systemPrompt).build(),
                    ChatMessage.builder().role("user").content(conversation.toString()).build()
            );

            var response = client.chatCompletion(context, SamplingParams.withTemperature(0.3));
            String content = response.getContent();
            if (content == null || content.isBlank()) return;

            JsonNode json = parseJson(content);
            if (json == null) return;

            String skipReason = json.has("skipReason") && !json.get("skipReason").isNull()
                    ? json.get("skipReason").asText() : null;
            if (skipReason != null) {
                log.debug("[Retrospect] 세션 {} 스킵: {}", sessionId, skipReason);
                return;
            }

            List<SectionUpdate> sectionUpdates = extractSectionUpdates(json);
            List<String> operationalInsights = extractStringArray(json, "operationalInsights");

            if (sectionUpdates.isEmpty() && operationalInsights.isEmpty()) return;

            if (!sectionUpdates.isEmpty()) {
                mdWriteLock.lock();
                try {
                    updateUserMd(sectionUpdates);
                } finally {
                    mdWriteLock.unlock();
                }
            }
            if (!operationalInsights.isEmpty()) {
                ingestToMemory(operationalInsights, sessionId);
            }

            log.info("[Retrospect] 세션 {} 회고 완료: sections={}, operational={}",
                    sessionId, sectionUpdates.size(), operationalInsights.size());

        } catch (Exception e) {
            log.warn("[Retrospect] 세션 {} 회고 실패: {}", sessionId, e.getMessage());
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

            ingestToMemory(insights, null);

            log.info("[Retrospect] Task '{}' 회고 완료: insights={}", taskTitle, insights.size());

        } catch (Exception e) {
            log.warn("[Retrospect] Task '{}' 회고 실패: {}", taskTitle, e.getMessage());
        }
    }

    // ── USER.md 섹션 관리 ───────────────────────

    private record SectionUpdate(String header, String content) {}

    /**
     * USER.md를 섹션 단위로 파싱.
     * 반환: (header → content) 순서 보존 맵. 헤더 없는 프리앰블은 빈 문자열 키.
     */
    private LinkedHashMap<String, String> parseMdSections(String markdown) {
        LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        if (markdown == null || markdown.isBlank()) return sections;

        String[] lines = markdown.split("\n", -1);
        String currentHeader = "";
        StringBuilder content = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("## ") || line.startsWith("### ")) {
                sections.put(currentHeader, content.toString());
                currentHeader = line;
                content = new StringBuilder();
            } else {
                content.append(line).append("\n");
            }
        }
        sections.put(currentHeader, content.toString());

        return sections;
    }

    /**
     * USER.md에 섹션 업데이트 적용.
     * 기존 섹션: content 교체. 새 섹션: ###은 마지막 ### 뒤, ##은 파일 끝.
     */
    private void updateUserMd(List<SectionUpdate> updates) {
        String current = persistentContextReader.readUserMd();
        LinkedHashMap<String, String> sections = parseMdSections(current != null ? current : "");

        for (SectionUpdate update : updates) {
            String header = update.header().strip();
            String newContent = update.content().strip() + "\n";

            if (sections.containsKey(header)) {
                sections.put(header, newContent);
            } else if (header.startsWith("### ")) {
                insertSubSection(sections, header, newContent);
            } else {
                sections.put(header, newContent);
            }
        }

        persistentContextReader.writeUserMd(reassembleMd(sections));
        log.debug("[Retrospect] USER.md 갱신: {}개 섹션", sections.size());
    }

    /**
     * 새 ### 섹션을 마지막 ### 뒤에 삽입.
     */
    private void insertSubSection(LinkedHashMap<String, String> sections, String header, String content) {
        List<String> keys = new ArrayList<>(sections.keySet());
        int insertAfter = -1;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).startsWith("### ")) {
                insertAfter = i;
            }
        }

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (insertAfter < 0) {
            // 기존 ### 없음 — 파일 끝에 추가
            result.putAll(sections);
            result.put(header, content);
        } else {
            for (int i = 0; i < keys.size(); i++) {
                result.put(keys.get(i), sections.get(keys.get(i)));
                if (i == insertAfter) {
                    result.put(header, content);
                }
            }
        }

        sections.clear();
        sections.putAll(result);
    }

    /**
     * 섹션 맵을 마크다운 문자열로 재조합.
     */
    private String reassembleMd(LinkedHashMap<String, String> sections) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            String header = entry.getKey();
            String content = entry.getValue().stripTrailing();

            if (!header.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(header).append("\n");
            }
            if (!content.isEmpty()) {
                sb.append(content).append("\n");
            }
        }
        return sb.toString();
    }

    private List<SectionUpdate> extractSectionUpdates(JsonNode json) {
        List<SectionUpdate> updates = new ArrayList<>();
        if (json.has("sectionUpdates") && json.get("sectionUpdates").isArray()) {
            for (JsonNode item : json.get("sectionUpdates")) {
                String header = item.has("header") ? item.get("header").asText() : null;
                String content = item.has("content") ? item.get("content").asText() : null;
                if (header != null && !header.isBlank() && content != null) {
                    updates.add(new SectionUpdate(header, content));
                }
            }
        }
        return updates;
    }

    // ── Memory 모듈 ingest ───────────────────────

    private void ingestToMemory(List<String> insights, String sessionId) {
        MemoryService memoryService = memoryServiceProvider.getIfAvailable();
        if (memoryService == null) {
            log.warn("[Retrospect] MemoryService 비활성 — 인사이트 {}건 저장 불가", insights.size());
            return;
        }

        for (String insight : insights) {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("source", "retrospect");
            metadata.put("type", "operational_insight");
            if (sessionId != null) {
                metadata.put("sessionId", sessionId);
            }
            memoryService.ingest(insight, metadata);
        }
    }

    // ── 공통 유틸리티 ───────────────────────

    private JsonNode parseJson(String content) {
        try {
            // 마크다운 코드블록 제거
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
