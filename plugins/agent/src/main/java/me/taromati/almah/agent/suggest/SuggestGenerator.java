package me.taromati.almah.agent.suggest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.permission.PermissionGate;
import me.taromati.almah.agent.service.AgentContextBuilder;
import me.taromati.almah.agent.service.AgentSessionService;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.agent.tool.AgentToolContext;
import me.taromati.almah.core.messenger.InteractiveMessage;
import me.taromati.almah.core.messenger.InteractiveMessage.Action;
import me.taromati.almah.core.messenger.InteractiveMessage.ActionStyle;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.llm.tool.ToolCallingService;
import me.taromati.almah.llm.tool.ToolExecutionFilter;
import me.taromati.almah.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SuggestGenerator {

    private final TaskStoreService taskStoreService;
    private final PermissionGate permissionGate;
    private final AgentContextBuilder contextBuilder;
    private final AgentConfigProperties config;
    private final LlmClientResolver clientResolver;
    private final ToolCallingService toolCallingService;
    private final SuggestHistory suggestHistory;
    private final MessengerGatewayRegistry messengerRegistry;
    private final SuggestVerifier suggestVerifier;
    private final AgentSessionService sessionService;
    private final StimulusCollectorChain stimulusChain;
    private final TopicSelector topicSelector;
    private final CuriosityScorer curiosityScorer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private MemoryService memoryService;

    public SuggestGenerator(TaskStoreService taskStoreService, PermissionGate permissionGate,
                            AgentContextBuilder contextBuilder, AgentConfigProperties config,
                            LlmClientResolver clientResolver, ToolCallingService toolCallingService,
                            SuggestHistory suggestHistory, MessengerGatewayRegistry messengerRegistry,
                            SuggestVerifier suggestVerifier, AgentSessionService sessionService,
                            StimulusCollectorChain stimulusChain, TopicSelector topicSelector,
                            CuriosityScorer curiosityScorer) {
        this.taskStoreService = taskStoreService;
        this.permissionGate = permissionGate;
        this.contextBuilder = contextBuilder;
        this.config = config;
        this.clientResolver = clientResolver;
        this.toolCallingService = toolCallingService;
        this.suggestHistory = suggestHistory;
        this.messengerRegistry = messengerRegistry;
        this.suggestVerifier = suggestVerifier;
        this.sessionService = sessionService;
        this.stimulusChain = stimulusChain;
        this.topicSelector = topicSelector;
        this.curiosityScorer = curiosityScorer;
    }

    public void generate() {
        String providerName = config.getSuggestProvider();
        LlmClient client = clientResolver.resolve(providerName);

        AgentToolContext.set("suggest", false, false, client, providerName, AgentToolContext.ExecutionContext.SUGGEST);

        try {
            curiosityScorer.clearCache();

            // ── Stage 1: Stimulus Collection ──
            StimulusResult stimulus = stimulusChain.collect();

            // ── Stage 2: Topic Selection ──
            TopicCandidate selectedTopic = null;
            List<TopicCandidate> topicCandidates = List.of();
            String category = stimulus.category() != null ? stimulus.category().name() : null;

            if (!stimulus.isEmpty()) {
                topicCandidates = topicSelector.selectTopics(stimulus);
                if (!topicCandidates.isEmpty()) {
                    selectedTopic = topicCandidates.getFirst();
                }
            }

            // ── Stage 3: Content Generation ──
            String suggestion;
            if (selectedTopic != null) {
                suggestion = generateContent(selectedTopic, stimulus, client);
            } else {
                // Stage 1~2 실패 → 기존 buildSuggestPrompt() 폴백
                log.info("[SuggestGenerator] 파이프라인 폴백: 기존 프롬프트 사용");
                suggestion = generateLegacy(client);
                category = null; // 폴백 시 카테고리 미지정
            }

            if (suggestion == null || suggestion.isBlank() || suggestion.contains("ROUTINE_OK")) {
                return;
            }

            // ── Stage 4: Quality Gate ──

            // 4a. Curiosity Score 하드 게이트
            if (!curiosityScorer.passes(suggestion)) {
                // 재시도: 다음 후보로
                if (topicCandidates.size() > 1) {
                    log.info("[SuggestGenerator] Curiosity 미통과, 2번째 후보로 재시도");
                    suggestion = generateContent(topicCandidates.get(1), stimulus, client);
                    if (suggestion == null || suggestion.isBlank() || suggestion.contains("ROUTINE_OK")
                            || !curiosityScorer.passes(suggestion)) {
                        suggestHistory.save(suggestion != null ? suggestion : "", "REJECTED_CURIOSITY", null, category);
                        log.info("[SuggestGenerator] Curiosity 재시도도 미통과, 폐기");
                        return;
                    }
                } else {
                    suggestHistory.save(suggestion, "REJECTED_CURIOSITY", null, category);
                    log.info("[SuggestGenerator] Curiosity 미통과, 폐기");
                    return;
                }
            }

            // 4b. 제안 후보 추출
            List<SuggestVerifier.SuggestCandidate> candidates = extractCandidates(suggestion);
            if (candidates.isEmpty()) {
                log.debug("[SuggestGenerator] ACTION 블록 없는 텍스트, 무시");
                return;
            }

            // 4c. SuggestVerifier 검증 (+REJECT_LOW_VALUE)
            List<SuggestVerifier.VerificationResult> verificationResults;
            try {
                var ctx = buildVerificationContext();
                verificationResults = suggestVerifier.verify(candidates, ctx);
            } catch (Exception e) {
                log.warn("[SuggestGenerator] 검증 실패, Task 미생성: {}", e.getMessage());
                suggestHistory.save(suggestion, "VERIFY_FAILED", null, category);
                return;
            }

            List<SuggestVerifier.VerificationResult> passedResults = verificationResults.stream()
                    .filter(r -> r.verdict() == SuggestVerifier.Verdict.PASS)
                    .toList();

            if (passedResults.isEmpty()) {
                suggestHistory.save(suggestion, "REJECTED", null, category);
                for (var r : verificationResults) {
                    log.info("[SuggestGenerator] 제안 거부: {} — {} ({})",
                            r.title(), r.verdict(), r.reason());
                }
                return;
            }

            // ── Stage 5: Approval (Phase 0) ──
            saveToSession(suggestion);
            String passedActionsJson = buildPassedActionsJson(passedResults);
            String suggestId = suggestHistory.save(suggestion, "PENDING", passedActionsJson, category);

            try {
                sendApprovalRequest(suggestion, passedResults, suggestId);
            } catch (Exception e) {
                log.warn("[SuggestGenerator] 발송 실패: {}", e.getMessage());
                suggestHistory.save(suggestion, "SEND_FAILED", null, category);
                return;
            }

            log.info("[SuggestGenerator] 승인 요청 발송: {}", StringUtils.truncate(suggestion, 100));

        } catch (Exception e) {
            log.warn("[SuggestGenerator] 제안 생성 실패: {}", e.getMessage());
        } finally {
            curiosityScorer.clearCache();
            AgentToolContext.clear();
        }
    }

    /**
     * Stage 3: 선택된 주제로 Content Generation.
     */
    private String generateContent(TopicCandidate topic, StimulusResult stimulus, LlmClient client) {
        String prompt = buildContentPrompt(topic, stimulus);
        return executeLlmToolCalling(prompt, client);
    }

    /**
     * 기존 generate() 폴백: Stage 1~2 실패 시 기존 프롬프트로 LLM 호출.
     */
    private String generateLegacy(LlmClient client) {
        String prompt = buildSuggestPrompt();
        return executeLlmToolCalling(prompt, client);
    }

    /**
     * LLM tool calling 실행 (Stage 3 + 폴백 공용).
     */
    private String executeLlmToolCalling(String prompt, LlmClient client) {
        String systemContent = contextBuilder.buildSystemContent(
                config.getSystemPrompt(), null, client);

        List<ChatMessage> context = List.of(
                ChatMessage.builder().role("system").content(systemContent).build(),
                ChatMessage.builder().role("user").content(prompt).build()
        );

        List<String> tools = new ArrayList<>(List.of("web_search", "web_fetch", "glob", "grep",
                "file_read", "memory_search", "memory_get", "tool_search"));

        ToolExecutionFilter filter = permissionGate.createSuggestFilter(null);

        var effective = config.resolveSessionConfig(client.getCapabilities());
        SamplingParams params = new SamplingParams(
                effective.maxTokens(), config.getTemperature(),
                config.getTopP(), config.getMinP(),
                config.getFrequencyPenalty(), config.getRepetitionPenalty(), null);
        var callingConfig = new ToolCallingService.ToolCallingConfig(
                effective.contextWindow(), effective.charsPerToken(), 10);

        var result = toolCallingService.chatWithTools(
                context, params, tools, filter, client, null, callingConfig);

        return result.textResponse();
    }

    /**
     * Stage 3 프롬프트: 선택된 주제에 대한 닫힌 지시.
     */
    String buildContentPrompt(TopicCandidate topic, StimulusResult stimulus) {
        StringBuilder sb = new StringBuilder();
        sb.append("[자율 사고] 다음 주제에 대해 구체적인 작업을 제안하세요.\n\n");
        sb.append("## 주제\n");
        sb.append(topic.topic()).append("\n");
        if (topic.rationale() != null) {
            sb.append("근거: ").append(topic.rationale()).append("\n");
        }
        sb.append("\n## 배경 정보\n");
        sb.append(stimulus.context()).append("\n\n");
        sb.append("웹 검색, 로컬 파일, 메모리 등을 탐색하여 이 주제에 대한 정보를 수집하고,\n");
        sb.append("구체적인 실행 계획을 세우세요.\n\n");
        sb.append("유용한 작업이 있으면 제안하세요.\n");
        sb.append("- `[ACTION: 제목]` = 자동 실행 가능\n");
        sb.append("  [ACTION: 제목]\n  [CONTEXT]\n  수집한 정보\n  [PLAN]\n  1. 도구명: 실행 단계\n  [/ACTION]\n");
        sb.append("- 없으면 ROUTINE_OK\n");
        return sb.toString();
    }

    // ── Phase 0 코드 (변경 없음) ──

    private void sendApprovalRequest(String suggestion,
                                      List<SuggestVerifier.VerificationResult> passedResults,
                                      String suggestId) {
        String text = "\uD83D\uDCA1 **[자율 제안]**\n" + extractDiscordMessage(suggestion);

        List<Action> actions = new ArrayList<>();
        String idPrefix = suggestId.length() >= 8 ? suggestId.substring(0, 8) : suggestId;

        if (passedResults.size() > 1) {
            for (int i = 0; i < passedResults.size(); i++) {
                actions.add(new Action(
                        "agent-suggest-approve:" + idPrefix + ":" + i,
                        "\u2705 " + StringUtils.truncate(passedResults.get(i).title(), 20),
                        ActionStyle.SUCCESS));
            }
            actions.add(new Action(
                    "agent-suggest-approve-all:" + idPrefix,
                    "\u2705 전체 승인", ActionStyle.PRIMARY));
        } else {
            actions.add(new Action(
                    "agent-suggest-approve:" + idPrefix + ":0",
                    "승인", ActionStyle.SUCCESS));
        }
        actions.add(new Action(
                "agent-suggest-deny:" + idPrefix,
                "거부", ActionStyle.DANGER));

        var message = new InteractiveMessage(text, actions);
        var result = messengerRegistry.broadcastInteractive(config.getChannelName(), message);
        if (result != null) {
            suggestHistory.updateMessageInfo(suggestId,
                    result.channel().serialize(), result.handle().getMessageId());
        }
    }

    private void saveToSession(String suggestion) {
        try {
            var session = sessionService.getOrCreateActiveSession(config.getChannelName());
            String sessionId = session != null ? session.getId() : null;
            sessionService.saveMessage(sessionId, "assistant",
                    "[자율 제안]\n" + suggestion, null, null);
        } catch (Exception e) {
            log.warn("[SuggestGenerator] 세션 기록 실패: {}", e.getMessage());
        }
    }

    private String buildPassedActionsJson(List<SuggestVerifier.VerificationResult> passedResults) {
        try {
            var actions = passedResults.stream()
                    .map(r -> Map.of(
                            "title", r.title() != null ? r.title() : "",
                            "description", r.description() != null ? r.description() : ""))
                    .toList();
            return objectMapper.writeValueAsString(actions);
        } catch (JsonProcessingException e) {
            log.warn("[SuggestGenerator] passedActions JSON 생성 실패: {}", e.getMessage());
            return "[]";
        }
    }

    // ── 폴백용 기존 프롬프트 (Stage 1~2 실패 시) ──

    private String buildSuggestPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("[자율 사고] 사용자 프로필의 관심사를 분석하여 가치 있는 작업을 판단하는 엔진입니다.\n\n");
        sb.append("사용자 프로필의 관심사 섹션을 참고하여 방향성 있는 작업을 판단하세요.\n");
        sb.append("- 관심사 기반 리서치 시점 판단 (최신 동향, 변경사항 확인)\n");
        sb.append("- 프로젝트 개선점 발견\n");
        sb.append("- 사용자 취향 기반 추가 관심사 추출\n\n");
        sb.append("웹 검색, 로컬 파일, 메모리 등 다양한 소스를 탐색하여 정보를 수집하세요.\n\n");
        sb.append("유용한 작업이 있으면 제안하세요.\n");
        sb.append("- `[ACTION: 제목]` = 자동 실행 가능\n");
        sb.append("  [ACTION: 제목]\n  [CONTEXT]\n  수집한 정보\n  [PLAN]\n  1. 도구명: 실행 단계\n  [/ACTION]\n");
        sb.append("- 사용자 확인이 필요한 제안은 [SUGGEST: 설명]으로만 제시\n");
        sb.append("- 없으면 ROUTINE_OK\n\n");

        var tasks = taskStoreService.findAll();
        if (!tasks.isEmpty()) {
            sb.append("---\n## 현재 할 일 목록\n");
            for (var task : tasks) {
                sb.append("- [").append(task.getStatus()).append("] ")
                        .append(task.getTitle()).append("\n");
            }
            sb.append("\n");
        }

        var completedTasks = taskStoreService.findByStatus("COMPLETED");
        if (!completedTasks.isEmpty()) {
            sb.append("---\n## 최근 완료된 할 일\n");
            int limit = Math.min(completedTasks.size(), 10);
            for (int i = 0; i < limit; i++) {
                sb.append("- ").append(completedTasks.get(i).getTitle()).append("\n");
            }
            sb.append("\n");
        }

        var recentSuggestions = suggestHistory.getRecent();
        if (!recentSuggestions.isEmpty()) {
            sb.append("---\n## 최근 제안 이력 (중복 제안 금지)\n");
            int limit = Math.min(recentSuggestions.size(), 10);
            for (int i = 0; i < limit; i++) {
                var s = recentSuggestions.get(i);
                String response = s.getResponse() != null ? s.getResponse() : "미응답";
                sb.append("- [").append(response).append("] ")
                        .append(StringUtils.truncate(s.getContent(), 100)).append("\n");
            }
            sb.append("\n");
        }

        if (memoryService != null) {
            try {
                var results = memoryService.search("최근 활동 요약");
                if (!results.isEmpty()) {
                    sb.append("---\n## 메모리 컨텍스트 (최근 활동)\n");
                    int limit = Math.min(results.size(), 5);
                    for (int i = 0; i < limit; i++) {
                        sb.append("- ").append(StringUtils.truncate(results.get(i).content(), 150)).append("\n");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                log.debug("[SuggestGenerator] 메모리 조회 실패: {}", e.getMessage());
            }
        }

        return sb.toString();
    }

    // ── 유틸리티 ──

    String extractDiscordMessage(String text) {
        String cleaned = text.replaceAll(
                "\\[ACTION:\\s*(.+?)]([\\s\\S]*?)\\[/ACTION]", "").trim();

        if (cleaned.isBlank()) {
            Pattern titlePattern = Pattern.compile("\\[ACTION:\\s*(.+?)]");
            Matcher titleMatcher = titlePattern.matcher(text);
            List<String> titles = new ArrayList<>();
            while (titleMatcher.find()) {
                titles.add(titleMatcher.group(1).trim());
            }
            if (!titles.isEmpty()) {
                return "작업을 제안합니다: " + String.join(", ", titles);
            }
            return text;
        }

        return cleaned;
    }

    List<SuggestVerifier.SuggestCandidate> extractCandidates(String suggestion) {
        List<SuggestVerifier.SuggestCandidate> candidates = new ArrayList<>();

        Pattern blockPattern = Pattern.compile(
                "\\[ACTION:\\s*(.+?)]([\\s\\S]*?)\\[/ACTION]", Pattern.MULTILINE);
        Matcher blockMatcher = blockPattern.matcher(suggestion);
        while (blockMatcher.find()) {
            String title = blockMatcher.group(1).trim();
            String description = parseBlockDescription(blockMatcher.group(2));
            candidates.add(new SuggestVerifier.SuggestCandidate(title, description));
        }

        if (candidates.isEmpty()) {
            Pattern legacyPattern = Pattern.compile("\\[ACTION:\\s*(.+?)]");
            Matcher legacyMatcher = legacyPattern.matcher(suggestion);
            while (legacyMatcher.find()) {
                candidates.add(new SuggestVerifier.SuggestCandidate(
                        legacyMatcher.group(1).trim(), null));
            }
        }

        return candidates;
    }

    private String parseBlockDescription(String blockBody) {
        if (blockBody == null || blockBody.isBlank()) return null;

        String contextStr = null;
        String plan = null;

        int contextIdx = blockBody.indexOf("[CONTEXT]");
        int planIdx = blockBody.indexOf("[PLAN]");

        if (contextIdx >= 0 && planIdx >= 0) {
            contextStr = blockBody.substring(contextIdx + "[CONTEXT]".length(), planIdx).trim();
            plan = blockBody.substring(planIdx + "[PLAN]".length()).trim();
        } else if (contextIdx >= 0) {
            contextStr = blockBody.substring(contextIdx + "[CONTEXT]".length()).trim();
        } else if (planIdx >= 0) {
            plan = blockBody.substring(planIdx + "[PLAN]".length()).trim();
        } else {
            return null;
        }

        StringBuilder desc = new StringBuilder();
        if (contextStr != null && !contextStr.isBlank()) {
            desc.append("[CONTEXT]\n").append(contextStr);
        }
        if (plan != null && !plan.isBlank()) {
            if (!desc.isEmpty()) desc.append("\n");
            desc.append("[PLAN]\n").append(plan);
        }
        return desc.isEmpty() ? null : desc.toString();
    }

    private SuggestVerifier.VerificationContext buildVerificationContext() {
        var activeTasks = taskStoreService.findAll().stream()
                .filter(t -> !me.taromati.almah.agent.task.TaskStatus.isTerminal(t.getStatus()))
                .map(t -> t.getTitle())
                .toList();
        var completedTasks = taskStoreService.findByStatus("COMPLETED").stream()
                .map(t -> t.getTitle())
                .limit(10)
                .toList();
        var recentSugs = suggestHistory.getRecent().stream()
                .map(s -> StringUtils.truncate(s.getContent(), 100))
                .limit(10)
                .toList();
        return new SuggestVerifier.VerificationContext(activeTasks, completedTasks, recentSugs);
    }
}
