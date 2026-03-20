package me.taromati.almah.agent.suggest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.permission.PermissionGate;
import me.taromati.almah.agent.service.AgentContextBuilder;
import me.taromati.almah.agent.task.TaskSource;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.agent.tool.AgentToolContext;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
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

    @Autowired(required = false)
    private MemoryService memoryService;

    public void generate() {
        String providerName = config.getSuggestProvider();
        LlmClient client = clientResolver.resolve(providerName);

        AgentToolContext.set("suggest", false, false, client, providerName, AgentToolContext.ExecutionContext.SUGGEST);

        try {
            String prompt = buildSuggestPrompt();
            String systemContent = contextBuilder.buildSystemContent(
                    config.getSystemPrompt(), null, client);

            List<ChatMessage> context = List.of(
                    ChatMessage.builder().role("system").content(systemContent).build(),
                    ChatMessage.builder().role("user").content(prompt).build()
            );

            // 읽기 전용 도구 + MCP 온디맨드 로딩 (suggest 모드)
            List<String> tools = new ArrayList<>(List.of("web_search", "web_fetch", "glob", "grep",
                    "file_read", "memory_search", "memory_get", "mcp_tools_load"));

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

            String suggestion = result.textResponse();
            if (suggestion != null && !suggestion.isBlank() && !suggestion.contains("ROUTINE_OK")) {
                // 이력 저장
                suggestHistory.save(suggestion);

                // Discord 전송
                sendSuggestion(suggestion);

                // [ACTION: 제목] 패턴에서 Task 자동 생성
                createSuggestTasks(suggestion);

                log.info("[SuggestGenerator] 자율 제안 발송: {}", StringUtils.truncate(suggestion, 100));
            }
        } catch (Exception e) {
            log.warn("[SuggestGenerator] 제안 생성 실패: {}", e.getMessage());
        } finally {
            AgentToolContext.clear();
        }
    }

    private String buildSuggestPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("[자율 사고] 사용자 프로필의 관심사를 분석하여 가치 있는 작업을 판단하는 엔진입니다.\n\n");
        sb.append("사용자 프로필의 관심사 섹션을 참고하여 방향성 있는 작업을 판단하세요.\n");
        sb.append("- 관심사 기반 리서치 시점 판단 (최신 동향, 변경사항 확인)\n");
        sb.append("- 프로젝트 개선점 발견\n");
        sb.append("- 사용자 취향 기반 추가 관심사 추출\n\n");
        sb.append("웹 검색, 로컬 파일, 메모리 등 다양한 소스를 탐색하여 정보를 수집하세요.\n\n");
        sb.append("유용한 작업이 있으면 제안하세요.\n");
        sb.append("- `[ACTION: 제목]` = 자동 실행 가능 (도구를 사용하여 직접 처리)\n");
        sb.append("  더 효과적인 실행을 위해 CONTEXT와 PLAN을 포함할 수 있습니다:\n");
        sb.append("  [ACTION: 제목]\n");
        sb.append("  [CONTEXT]\n");
        sb.append("  수집한 정보, 판단 근거\n");
        sb.append("  [PLAN]\n");
        sb.append("  1. 도구명: 구체적 실행 단계\n");
        sb.append("  2. 도구명: 구체적 실행 단계\n");
        sb.append("  [/ACTION]\n");
        sb.append("- 사용자 확인이 필요한 제안은 [SUGGEST: 설명]으로만 제시\n");
        sb.append("- 없으면 ROUTINE_OK\n\n");

        // 할 일 목록 참고
        var tasks = taskStoreService.findAll();
        if (!tasks.isEmpty()) {
            sb.append("---\n## 현재 할 일 목록\n");
            for (var task : tasks) {
                sb.append("- [").append(task.getStatus()).append("] ")
                        .append(task.getTitle()).append("\n");
            }
            sb.append("\n");
        }

        // 최근 완료된 할 일 (중복 제안 방지 + 맥락)
        var completedTasks = taskStoreService.findByStatus("COMPLETED");
        if (!completedTasks.isEmpty()) {
            sb.append("---\n## 최근 완료된 할 일\n");
            int limit = Math.min(completedTasks.size(), 10);
            for (int i = 0; i < limit; i++) {
                var task = completedTasks.get(i);
                sb.append("- ").append(task.getTitle()).append("\n");
            }
            sb.append("\n");
        }

        // 최근 제안 이력 (중복 방지)
        var recentSuggestions = suggestHistory.getRecent();
        if (!recentSuggestions.isEmpty()) {
            sb.append("---\n## 최근 제안 이력 (중복 제안 금지)\n");
            int limit = Math.min(recentSuggestions.size(), 10);
            for (int i = 0; i < limit; i++) {
                var suggestion = recentSuggestions.get(i);
                String response = suggestion.getResponse() != null ? suggestion.getResponse() : "미응답";
                sb.append("- [").append(response).append("] ")
                        .append(StringUtils.truncate(suggestion.getContent(), 100)).append("\n");
            }
            sb.append("\n");
        }

        // 메모리 컨텍스트 (최근 에피소드 요약)
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

    // package-private for testing
    void createSuggestTasks(String suggestion) {
        int dailyLimit = config.getSuggest().getDailyTaskLimit();
        int created = 0;

        // 1. 멀티라인 블록 파싱: [ACTION: title]...[/ACTION]
        Pattern blockPattern = Pattern.compile(
                "\\[ACTION:\\s*(.+?)]([\\s\\S]*?)\\[/ACTION]", Pattern.MULTILINE);
        Matcher blockMatcher = blockPattern.matcher(suggestion);

        while (blockMatcher.find() && created < dailyLimit) {
            String title = blockMatcher.group(1).trim();
            String blockBody = blockMatcher.group(2);
            String description = parseBlockDescription(blockBody);

            if (taskStoreService.existsActiveByTitle(title)) continue;
            taskStoreService.create(title, description,
                    null, TaskSource.SUGGEST, config.getSuggest().getTaskMaxRetries());
            created++;
            log.info("[SuggestGenerator] Task 자동 생성 (블록): {}", title);
        }

        // 2. 하위호환: [/ACTION] 없는 단일행 [ACTION: title]
        if (created == 0) {
            Pattern legacyPattern = Pattern.compile("\\[ACTION:\\s*(.+?)]");
            Matcher legacyMatcher = legacyPattern.matcher(suggestion);
            while (legacyMatcher.find() && created < dailyLimit) {
                String title = legacyMatcher.group(1).trim();
                if (taskStoreService.existsActiveByTitle(title)) continue;
                // 블록으로 이미 처리된 제목은 스킵
                taskStoreService.create(title, "자율 제안에서 자동 생성",
                        null, TaskSource.SUGGEST, config.getSuggest().getTaskMaxRetries());
                created++;
                log.info("[SuggestGenerator] Task 자동 생성 (레거시): {}", title);
            }
        }
    }

    private String parseBlockDescription(String blockBody) {
        if (blockBody == null || blockBody.isBlank()) return null;

        String context = null;
        String plan = null;

        int contextIdx = blockBody.indexOf("[CONTEXT]");
        int planIdx = blockBody.indexOf("[PLAN]");

        if (contextIdx >= 0 && planIdx >= 0) {
            context = blockBody.substring(contextIdx + "[CONTEXT]".length(), planIdx).trim();
            plan = blockBody.substring(planIdx + "[PLAN]".length()).trim();
        } else if (contextIdx >= 0) {
            context = blockBody.substring(contextIdx + "[CONTEXT]".length()).trim();
        } else if (planIdx >= 0) {
            plan = blockBody.substring(planIdx + "[PLAN]".length()).trim();
        } else {
            return null;
        }

        StringBuilder desc = new StringBuilder();
        if (context != null && !context.isBlank()) {
            desc.append("[CONTEXT]\n").append(context);
        }
        if (plan != null && !plan.isBlank()) {
            if (!desc.isEmpty()) desc.append("\n");
            desc.append("[PLAN]\n").append(plan);
        }
        return desc.isEmpty() ? null : desc.toString();
    }

    private void sendSuggestion(String text) {
        String discordMessage = extractDiscordMessage(text);
        messengerRegistry.broadcastText(config.getChannelName(),
                "\uD83D\uDCA1 **[자율 제안]**\n" + discordMessage);
    }

    /**
     * Discord에 전송할 메시지 생성.
     * ACTION 블록을 제거하고, 블록만 있으면 간략 메시지 생성.
     */
    // package-private for testing
    String extractDiscordMessage(String text) {
        // ACTION 블록 제거
        String cleaned = text.replaceAll(
                "\\[ACTION:\\s*(.+?)]([\\s\\S]*?)\\[/ACTION]", "").trim();

        if (cleaned.isBlank()) {
            // ACTION 블록만 있을 때 — 제목 추출하여 간략 메시지
            Pattern titlePattern = Pattern.compile("\\[ACTION:\\s*(.+?)]");
            Matcher titleMatcher = titlePattern.matcher(text);
            List<String> titles = new ArrayList<>();
            while (titleMatcher.find()) {
                titles.add(titleMatcher.group(1).trim());
            }
            if (!titles.isEmpty()) {
                return "작업을 등록했어요: " + String.join(", ", titles);
            }
            return text;
        }

        return cleaned;
    }
}
