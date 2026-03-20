package me.taromati.almah.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import me.taromati.almah.agent.db.entity.AgentSessionEntity;
import me.taromati.almah.agent.db.repository.AgentRoutineHistoryRepository;
import me.taromati.almah.agent.mcp.McpClientManager;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.core.util.TimeConstants;
import me.taromati.almah.llm.client.LlmClient;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;


/**
 * Agent 컨텍스트 빌더
 * DB 메시지를 LLM 호출용 ChatMessage 리스트로 변환합니다.
 * tool_calls/tool 메시지를 복원하여 tool chain을 컨텍스트에 포함합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentContextBuilder {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MESSAGE_OVERHEAD_CHARS = 10;
    private static final double SAFETY_MARGIN = 1.2;
    private static final int TOOL_RESULT_RECENT_KEEP = 6;
    private static final int TOOL_RESULT_TRUNCATE_CHARS = 100;
    private static final int MIN_KEEP_CHARS = 2000;
    private static final int MAX_TOOL_RESULT_CHARS_CAP = 400_000;
    private static final double TOOL_RESULT_RATIO = 0.3;
    private static final String TRUNCATION_SUFFIX = "\n[...결과가 잘렸습니다. 원본: %d자. 필요시 offset/limit으로 부분 요청하세요]";

    private final AgentConfigProperties config;
    private final PersistentContextReader persistentContextReader;
    private final ToolRegistry toolRegistry;

    @Autowired(required = false)
    private McpClientManager mcpClientManager;

    @Autowired(required = false)
    private MemoryService memoryService;

    @Autowired(required = false)
    private SkillManager skillManager;

    @Autowired(required = false)
    private AgentRoutineHistoryRepository routineHistoryRepository;

    /**
     * 시스템 프롬프트 전체 조립.
     * base prompt + 구조적 지침 + 영속 컨텍스트 + 요약을 하나의 문자열로 합칩니다.
     * AgentJobExecutor 등 외부에서도 동일한 시스템 프롬프트를 공유할 수 있습니다.
     */
    public String buildSystemContent(String basePrompt, String summary, LlmClient client) {
        return buildSystemContent(basePrompt, summary, client, null);
    }

    public String buildSystemContent(String basePrompt, String summary, LlmClient client, List<AgentMessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt);
        appendTimeInfo(sb);
        String guide = persistentContextReader.readGuideMd();
        if (guide != null && !guide.isEmpty()) {
            sb.append("\n\n---\n").append(guide);
        }
        appendPersistentContext(sb);
        appendMemoryContext(sb, messages);
        appendCuratedMemory(sb);
        appendUserProfile(sb);
        appendRoutineHistory(sb);
        appendProviderHints(sb, client);
        // 페르소나를 시스템 프롬프트 끝에 배치 (recency bias로 준수율 향상)
        String persona = persistentContextReader.readPersonaMd();
        if (persona != null && !persona.isEmpty()) {
            sb.append("\n\n---\n[페르소나 — 이 파일의 성격과 톤을 체화하세요. 수정 시: 핵심 성격 유지, 점진적 변경, 섹션 구조 보존]\n")
                    .append(persona);
        }
        if (summary != null && !summary.isEmpty()) {
            sb.append("\n\n[이전 대화 요약]\n").append(summary);
        }
        return sb.toString();
    }

    /**
     * 서브에이전트용 시스템 프롬프트 조립.
     * OpenClaw ALLOWLIST 패턴: GUIDE.md + TOOLS.md + 스킬 + MCP + providerHints.
     * PERSONA.md, MEMORY.md, Memory Context, 루틴 이력 제외.
     */
    public String buildSubagentSystemContent(String task, LlmClient client) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 서브에이전트입니다. 주어진 작업을 수행하고 결과를 보고하세요.");
        appendTimeInfo(sb);
        String guide = persistentContextReader.readGuideMd();
        if (guide != null && !guide.isEmpty()) {
            sb.append("\n\n---\n").append(guide);
        }
        appendPersistentContext(sb);
        appendProviderHints(sb, client);
        sb.append("\n\n작업: ").append(task);
        return sb.toString();
    }

    /**
     * DB 메시지 → LLM 컨텍스트 빌드
     */
    public List<ChatMessage> buildContext(String systemPrompt, AgentSessionEntity session,
                                          List<AgentMessageEntity> messages, LlmClient client,
                                          AgentConfigProperties.EffectiveSessionConfig effective) {
        List<ChatMessage> context = new ArrayList<>();

        // 1. 시스템 프롬프트 (공유 빌더 사용)
        String systemContent = buildSystemContent(systemPrompt, session.getSummary(), client, messages);
        context.add(ChatMessage.builder().role("system").content(systemContent).build());

        // 2. 메시지 변환 (maxContextMessages 적용 + 도구결과 소프트트림)
        int maxMessages = config.getMaxContextMessages();
        List<AgentMessageEntity> recentMessages = messages.size() > maxMessages
                ? messages.subList(messages.size() - maxMessages, messages.size())
                : messages;
        int recentBoundary = Math.max(0, recentMessages.size() - TOOL_RESULT_RECENT_KEEP);

        for (int i = 0; i < recentMessages.size(); i++) {
            AgentMessageEntity msg = recentMessages.get(i);
            if (i < recentBoundary && "tool".equals(msg.getRole())) {
                context.add(truncatedToolResponse(msg));
            } else {
                context.add(toContextMessage(msg));
            }
        }

        // 2.5. 개별 도구 결과 크기 제한 (최근 도구 결과가 컨텍스트를 독점하는 것 방지)
        int contextBudget = effective.contextWindow() - effective.maxTokens();
        int charsPerToken = effective.charsPerToken();
        truncateOversizedToolResults(context, contextBudget, charsPerToken);

        // 3. 고아 tool 메시지 사전 정리 (maxContextMessages 슬라이싱으로 인한 고아)
        cleanOrphanToolMessages(context);

        // 4. vLLM 교대 규칙 보장
        ensureFirstMessageIsUser(context);

        // 5. 오버플로우 보호 — 토큰 추정치가 budget 초과 시 트리밍
        int estimated = estimateContextTokens(context, charsPerToken);
        if (estimated > Math.max(contextBudget, 1)) {
            log.warn("[AgentContextBuilder] Context overflow: ~{} tokens > {} budget, trimming",
                    estimated, contextBudget);
            trimForOverflow(context, Math.max(contextBudget, 1), charsPerToken);
        }

        return context;
    }

    /**
     * AgentMessageEntity → ChatMessage 변환 (tool_calls JSON 복원)
     */
    private ChatMessage toContextMessage(AgentMessageEntity msg) {
        if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
            List<ChatCompletionResponse.ToolCall> toolCalls = parseToolCalls(msg.getToolCalls());
            if (toolCalls != null && !toolCalls.isEmpty()) {
                return ChatMessage.assistantWithToolCalls(toolCalls);
            }
        }
        if ("tool".equals(msg.getRole())) {
            return ChatMessage.toolResponse(msg.getToolCallId(), msg.getContent());
        }
        return ChatMessage.builder().role(msg.getRole()).content(msg.getContent()).build();
    }

    /**
     * 오래된 도구 결과를 placeholder로 교체 (Observation Masking).
     * JetBrains 연구 기반: 행동/추론 히스토리는 보존하면서 관찰 결과만 마스킹 → 비용 절감 + 성능 유지.
     */
    private ChatMessage truncatedToolResponse(AgentMessageEntity msg) {
        String content = msg.getContent();
        if (content == null) {
            return ChatMessage.toolResponse(msg.getToolCallId(), "[결과 없음]");
        }
        int lines = content.split("\n").length;
        return ChatMessage.toolResponse(msg.getToolCallId(),
                String.format("[실행 완료: %d줄, %d자]", lines, content.length()));
    }

    /**
     * 개별 도구 결과 크기 제한 (OpenClaw 패턴).
     * 최근 도구 결과 중 maxChars 초과 시 head + suffix 트렁케이션.
     * 줄바꿈 경계 우선 컷: keepChars × 0.8 이후 첫 줄바꿈에서 자름.
     */
    void truncateOversizedToolResults(List<ChatMessage> context, int contextBudget, int charsPerToken) {
        int maxChars = (int) (contextBudget * charsPerToken * TOOL_RESULT_RATIO);
        maxChars = Math.min(maxChars, MAX_TOOL_RESULT_CHARS_CAP);
        maxChars = Math.max(maxChars, MIN_KEEP_CHARS);

        for (int i = 0; i < context.size(); i++) {
            ChatMessage msg = context.get(i);
            if (!"tool".equals(msg.getRole())) continue;
            String content = msg.getContentAsString();
            if (content == null || content.length() <= maxChars) continue;

            String suffix = String.format(TRUNCATION_SUFFIX, content.length());
            int keepChars = maxChars - suffix.length();
            if (keepChars < 0) keepChars = 0;

            // 줄바꿈 경계 우선 컷
            int cutPoint = content.lastIndexOf('\n', keepChars);
            if (cutPoint < (int) (keepChars * 0.8)) cutPoint = keepChars;

            String truncated = content.substring(0, cutPoint) + suffix;
            context.set(i, ChatMessage.toolResponse(msg.getToolCallId(), truncated));
        }
    }

    /**
     * toolCalls JSON 파싱 → List<ToolCall>
     */
    private List<ChatCompletionResponse.ToolCall> parseToolCalls(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[AgentContextBuilder] Failed to parse toolCalls JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * vLLM 호환성: system 다음 첫 메시지가 user가 될 때까지 반복 삭제/병합.
     * - assistant(tool_calls): 그룹 전체 삭제 (함수 호출 메타정보는 system에 무의미)
     * - assistant(텍스트): system에 병합
     * - tool(고아): 단독 삭제
     */
    void ensureFirstMessageIsUser(List<ChatMessage> context) {
        while (context.size() >= 2 && !"user".equals(context.get(1).getRole())) {
            ChatMessage msg = context.get(1);
            if ("assistant".equals(msg.getRole())) {
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    removeMessageGroup(context, 1);
                } else {
                    String assistantContent = msg.getContentAsString();
                    if (assistantContent != null && !assistantContent.isEmpty()) {
                        String merged = context.get(0).getContentAsString()
                                + "\n\n[이전에 보낸 메시지]\n" + assistantContent;
                        context.set(0, ChatMessage.builder()
                                .role("system")
                                .content(merged)
                                .build());
                    }
                    context.remove(1);
                }
            } else if ("tool".equals(msg.getRole())) {
                context.remove(1);
            } else {
                break;
            }
        }
    }

    /**
     * 그룹 단위 메시지 삭제.
     * - assistant(tool_calls): 자신 + 바로 뒤 연속 tool 메시지(매칭 toolCallId) 함께 삭제
     * - tool: 역탐색으로 소속 assistant(tool_calls) 찾아 그룹 전체 삭제, 없으면 단독 삭제
     * - 그 외: 단일 삭제
     */
    void removeMessageGroup(List<ChatMessage> context, int index) {
        if (index < 0 || index >= context.size()) return;

        ChatMessage target = context.get(index);

        if ("assistant".equals(target.getRole()) && target.getToolCalls() != null && !target.getToolCalls().isEmpty()) {
            Set<String> ids = new HashSet<>();
            for (var tc : target.getToolCalls()) {
                if (tc.getId() != null) ids.add(tc.getId());
            }
            context.remove(index);
            // 뒤따르는 매칭 tool 메시지 삭제
            while (index < context.size()) {
                ChatMessage next = context.get(index);
                if ("tool".equals(next.getRole()) && ids.contains(next.getToolCallId())) {
                    context.remove(index);
                } else {
                    break;
                }
            }
        } else if ("tool".equals(target.getRole())) {
            String toolCallId = target.getToolCallId();
            int ownerIndex = findOwnerAssistant(context, index, toolCallId);
            if (ownerIndex >= 0) {
                removeMessageGroup(context, ownerIndex);
            } else {
                context.remove(index);
            }
        } else {
            context.remove(index);
        }
    }

    /**
     * tool 메시지의 소속 assistant(tool_calls) 역탐색
     */
    private int findOwnerAssistant(List<ChatMessage> context, int toolIndex, String toolCallId) {
        if (toolCallId == null) return -1;
        for (int i = toolIndex - 1; i >= 0; i--) {
            ChatMessage msg = context.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    if (toolCallId.equals(tc.getId())) return i;
                }
            }
            // assistant(텍스트) 또는 user를 만나면 탐색 중단 (tool 블록 경계)
            if (!"tool".equals(msg.getRole())) break;
        }
        return -1;
    }

    private void appendPersistentContext(StringBuilder sb) {
        String toolsMd = persistentContextReader.readToolsMd();
        if (toolsMd != null && !toolsMd.isEmpty()) {
            sb.append("\n\n---\n[도구 사용법]\n").append(toolsMd);
        }

        // SkillManager가 있으면 게이팅된 활성 스킬 사용, 없으면 기존 방식 fallback
        List<SkillFile> skills = skillManager != null
                ? skillManager.getActiveSkills()
                : persistentContextReader.readActiveSkills();
        if (!skills.isEmpty()) {
            sb.append("\n\n---\n[활성 스킬 — skill(view)로 실행 명령 확인 후 사용]");
            for (var skill : skills) {
                sb.append("\n- **").append(skill.name()).append("**");
                if (skill.description() != null && !skill.description().isEmpty()) {
                    sb.append(": ").append(skill.description());
                }
                if (!skill.tools().isEmpty()) {
                    sb.append(" [도구: ").append(String.join(", ", skill.tools())).append("]");
                }
                if (skill.mcpServer() != null) {
                    sb.append(" [MCP: ").append(skill.mcpServer()).append("]");
                }
            }
        }

        // INSTALL_REQUIRED 스킬도 노출 (Agent가 설치 시도 가능)
        if (skillManager != null) {
            var installRequired = skillManager.getInstallRequiredSkills();
            if (!installRequired.isEmpty()) {
                sb.append("\n\n---\n[설치 필요 스킬 — 사용자 요청 시 exec로 설치 후 사용 가능]");
                for (var cached : installRequired) {
                    var skill = cached.skillFile();
                    var gating = cached.gatingResult();
                    sb.append("\n- **").append(skill.name()).append("**");
                    if (skill.description() != null && !skill.description().isEmpty()) {
                        sb.append(": ").append(skill.description());
                    }
                    sb.append(" (").append(gating.reason()).append(")");
                    for (var spec : gating.installSpecs()) {
                        sb.append("\n  설치: `").append(spec.kind()).append(" install ").append(spec.formula()).append("`");
                        if (spec.label() != null) sb.append(" — ").append(spec.label());
                    }
                }
            }
        }

        appendMcpServerInfo(sb);
    }

    private void appendMcpServerInfo(StringBuilder sb) {
        if (mcpClientManager == null) return;
        var status = mcpClientManager.getDetailedStatus();
        if (status.isEmpty()) return;
        sb.append("\n\n---\n[MCP 서버]");
        for (var entry : status.entrySet()) {
            String name = entry.getKey();
            var info = entry.getValue();
            sb.append("\n- ").append(name).append(": ").append(info.get("state"));
            String defaultPolicy = String.valueOf(info.getOrDefault("defaultPolicy", "ask"));
            sb.append(" (정책: ").append(defaultPolicy).append(")");

            // 연결된 서버의 도구 카탈로그 (이름: 설명)
            if ("CONNECTED".equals(String.valueOf(info.get("state")))) {
                List<String> tools = mcpClientManager.getServerTools(name);
                if (!tools.isEmpty()) {
                    sb.append("\n  도구: ");
                    for (int i = 0; i < tools.size(); i++) {
                        if (i > 0) sb.append(", ");
                        String toolName = tools.get(i);
                        sb.append(toolName);
                        ChatCompletionRequest.ToolDefinition def = toolRegistry.getDefinition(toolName);
                        if (def != null && def.getFunction() != null && def.getFunction().getDescription() != null) {
                            sb.append("(").append(StringUtils.truncate(def.getFunction().getDescription(), 30)).append(")");
                        }
                    }
                }
            } else {
                Object toolCount = info.get("toolCount");
                if (toolCount != null) {
                    sb.append(", 도구 ").append(toolCount).append("개");
                }
            }
        }
        sb.append("\nMCP 도구 사용: mcp_tools_load(tools=[...]) 또는 mcp_tools_load(server=\"...\")로 로드 후 호출.");
    }

    private void appendMemoryContext(StringBuilder sb, List<AgentMessageEntity> messages) {
        if (memoryService == null) return;
        try {
            String query = buildMemoryQuery(messages);
            var results = memoryService.search(query);
            if (!results.isEmpty()) {
                sb.append("\n\n---\n[메모리 컨텍스트]");
                int limit = Math.min(results.size(), 5);
                for (int i = 0; i < limit; i++) {
                    sb.append("\n- ").append(results.get(i).content());
                }
            }
        } catch (Exception e) {
            log.debug("[AgentContextBuilder] 메모리 조회 실패: {}", e.getMessage());
        }
    }

    private String buildMemoryQuery(List<AgentMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) return "최근 대화 맥락";
        StringBuilder queryBuilder = new StringBuilder();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < 3; i--) {
            AgentMessageEntity msg = messages.get(i);
            if ("assistant".equals(msg.getRole())) break;
            if ("user".equals(msg.getRole())) {
                if (!queryBuilder.isEmpty()) queryBuilder.insert(0, " ");
                queryBuilder.insert(0, msg.getContent());
                count++;
            }
        }
        return queryBuilder.isEmpty() ? "최근 대화 맥락" : queryBuilder.toString();
    }

    private void appendCuratedMemory(StringBuilder sb) {
        String memoryMd = persistentContextReader.readMemoryMd();
        if (memoryMd != null && !memoryMd.isEmpty()) {
            sb.append("\n\n---\n[학습 기억]\n").append(memoryMd);
        }
    }

    private void appendUserProfile(StringBuilder sb) {
        String userMd = persistentContextReader.readUserMd();
        if (userMd != null && !userMd.isEmpty()) {
            sb.append("\n\n---\n[사용자 프로필]\n").append(userMd);
        }
    }

    private void appendRoutineHistory(StringBuilder sb) {
        if (routineHistoryRepository == null) return;
        try {
            var recent = routineHistoryRepository.findTop20ByOrderByCompletedAtDesc();
            if (recent.isEmpty()) return;
            sb.append("\n\n---\n[최근 활동 기록]");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M/d H:mm");
            for (var entry : recent) {
                sb.append("\n- ").append(entry.getCompletedAt().format(fmt)).append(" ");
                if ("FAILED".equals(entry.getStatus())) sb.append("[실패] ");
                sb.append(entry.getTitle());
                if (entry.getSummary() != null && !entry.getSummary().isEmpty()) {
                    sb.append(": ").append(StringUtils.truncate(entry.getSummary(), 100));
                }
            }
        } catch (Exception e) {
            log.debug("[AgentContextBuilder] 루틴 이력 조회 실패: {}", e.getMessage());
        }
    }

    private void appendProviderHints(StringBuilder sb, LlmClient client) {
        if (client == null) return;
        String hints = client.getSystemPromptHints();
        if (hints != null && !hints.isBlank()) {
            sb.append("\n\n---\n[프로바이더 보정 규칙]\n").append(hints.strip());
        }
    }

    private void appendTimeInfo(StringBuilder sb) {
        ZonedDateTime now = ZonedDateTime.now(TimeConstants.KST);
        String currentTime = now.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE H시 m분"));
        sb.append("\n\n현재 시각: ").append(currentTime);
    }

    /**
     * ChatMessage 리스트의 토큰 수를 문자 수 기반으로 추정
     */
    int estimateContextTokens(List<ChatMessage> context, int charsPerToken) {
        int totalChars = 0;
        for (ChatMessage msg : context) {
            totalChars += MESSAGE_OVERHEAD_CHARS;
            String text = msg.getContentAsString();
            if (text != null) totalChars += text.length();
            if (msg.getToolCalls() != null) {
                try {
                    totalChars += objectMapper.writeValueAsString(msg.getToolCalls()).length();
                } catch (Exception ignored) {
                    totalChars += 100; // fallback
                }
            }
        }
        return (int) Math.ceil((double) totalChars / Math.max(charsPerToken, 1) * SAFETY_MARGIN);
    }

    /**
     * 오버플로우 시 오래된 메시지부터 그룹 단위 삭제 (system[0] 보존)
     */
    void trimForOverflow(List<ChatMessage> context, int tokenLimit, int charsPerToken) {
        while (context.size() > 1 && estimateContextTokens(context, charsPerToken) > tokenLimit) {
            removeMessageGroup(context, 1);
        }
        cleanOrphanToolMessages(context);
        ensureFirstMessageIsUser(context);
    }

    /**
     * 고아 tool 메시지 정리
     * <ol>
     *   <li>assistant(tool_calls)에서 선언된 toolCallId 수집</li>
     *   <li>선언되지 않은 toolCallId를 가진 tool 메시지 삭제</li>
     *   <li>응답이 없는 assistant(tool_calls) 삭제</li>
     * </ol>
     */
    void cleanOrphanToolMessages(List<ChatMessage> context) {
        // Pass 1: assistant tool_calls에서 선언된 toolCallId 수집
        Set<String> declaredIds = new HashSet<>();
        for (ChatMessage msg : context) {
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    if (tc.getId() != null) declaredIds.add(tc.getId());
                }
            }
        }

        // Pass 2: 선언되지 않은 toolCallId를 가진 tool 메시지 삭제
        Set<String> presentToolResponseIds = new HashSet<>();
        Iterator<ChatMessage> it = context.iterator();
        while (it.hasNext()) {
            ChatMessage msg = it.next();
            if ("tool".equals(msg.getRole())) {
                if (msg.getToolCallId() == null || !declaredIds.contains(msg.getToolCallId())) {
                    it.remove();
                } else {
                    presentToolResponseIds.add(msg.getToolCallId());
                }
            }
        }

        // Pass 3: 응답이 하나도 없는 assistant(tool_calls) 삭제
        it = context.iterator();
        while (it.hasNext()) {
            ChatMessage msg = it.next();
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                boolean hasAnyResponse = msg.getToolCalls().stream()
                        .anyMatch(tc -> presentToolResponseIds.contains(tc.getId()));
                if (!hasAnyResponse) {
                    it.remove();
                }
            }
        }
    }
}
