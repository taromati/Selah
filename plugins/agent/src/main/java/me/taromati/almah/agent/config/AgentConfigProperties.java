package me.taromati.almah.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import me.taromati.almah.llm.client.ProviderCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "plugins.agent")
public class AgentConfigProperties {
    private Boolean enabled = false;
    private String channelName = "agent";
    private String dataDir = "./agent-data/";
    private String systemPrompt = "당신은 한국어로 대화하는 AI 에이전트입니다.";
    /** 글로벌 LLM 프로바이더 (openai, vllm 등). 반드시 명시적으로 설정해야 함. */
    private String llmProviderName;

    /** 루틴 실행용 프로바이더. null이면 llmProviderName 폴백. */
    private String routineProvider;
    /** 자율 제안용 프로바이더. null이면 llmProviderName 폴백. */
    private String suggestProvider;

    /** 기동 시 지정된 초기 LLM 프로바이더 (모델 초기화 커맨드에서 복원용). */
    private String initialLlmProviderName;

    public String getRoutineProvider() {
        return (routineProvider != null && !routineProvider.isBlank())
                ? routineProvider : llmProviderName;
    }

    public String getSuggestProvider() {
        return (suggestProvider != null && !suggestProvider.isBlank())
                ? suggestProvider : llmProviderName;
    }

    @PostConstruct
    public void validate() {
        if (Boolean.TRUE.equals(enabled) && (llmProviderName == null || llmProviderName.isBlank())) {
            throw new IllegalStateException(
                    "plugins.agent.llm-provider-name 가 설정되지 않았습니다. " +
                    "config.yml에 plugins.agent.llm-provider-name 값을 명시하세요 (예: openai, vllm).");
        }
        this.initialLlmProviderName = llmProviderName;
    }
    private GeminiConfig gemini = new GeminiConfig();
    private Integer maxContextMessages = 30;
    private Integer maxTokens = 4096;
    private Double temperature = 0.7;
    private Double topP;
    private Double minP;
    private Double frequencyPenalty;
    private Double repetitionPenalty;
    private ToolsConfig tools = new ToolsConfig();
    private ExecConfig exec = new ExecConfig();
    private FileConfig file = new FileConfig();
    private WebSearchConfig webSearch = new WebSearchConfig();
    private WebFetchConfig webFetch = new WebFetchConfig();
    private BrowserConfig browser = new BrowserConfig();
    private SessionConfig session = new SessionConfig();
    private SubagentConfig subagent = new SubagentConfig();
    private String voiceChannelName;
    private Integer voiceCallTimeoutSeconds = 600;
    private CronConfig cron = new CronConfig();
    private RoutineConfig routine = new RoutineConfig();
    private SuggestConfig suggest = new SuggestConfig();
    private RetrospectConfig retrospect = new RetrospectConfig();
    private TaskConfig task = new TaskConfig();
    private SkillConfig skill = new SkillConfig();
    private int streamingDebounceMs = 200;
    private int streamingSplitThreshold = 2000;

    /** 한 턴의 도구 루프 최대 시간 (분). 기본 5분. 0 이하 → 5. */
    private int maxDurationMinutes = 5;
    /** 이어하기 최대 횟수. 기본 3회. 0 이하 → 3. */
    private int maxContinuations = 3;
    /** 이어하기 승인 대기 시간 (분). 기본 2분. 0 이하 → 2. */
    private int continuationTimeoutMinutes = 2;

    public int getMaxDurationMinutes() {
        return maxDurationMinutes > 0 ? maxDurationMinutes : 5;
    }

    public int getMaxContinuations() {
        return maxContinuations > 0 ? maxContinuations : 3;
    }

    public int getContinuationTimeoutMinutes() {
        return continuationTimeoutMinutes > 0 ? continuationTimeoutMinutes : 2;
    }

    @Getter
    @Setter
    public static class SkillConfig {
        /** ClawHub 레지스트리 URL */
        private String clawhubRegistry = "https://clawhub.ai";
    }

    @Getter
    @Setter
    public static class SessionConfig {
        /** null → 프로바이더 값 → 32768 */
        private Integer contextWindow;
        private Double compactionRatio;
        /** null → 프로바이더 값 → contextWindow 비례 */
        private Integer recentKeep;
        /** null → 프로바이더 값 → 3 */
        private Integer charsPerToken;
        /** Task 유휴 타임아웃 (분). 기본 60분. */
        private Integer taskIdleTimeoutMinutes = 60;
        /** 세션 유휴 타임아웃 (분). 기본 240분 (4시간). 0이면 비활성화. */
        private Integer sessionIdleTimeoutMinutes = 240;
        /** 채널당 최대 비활성 세션 수. 0 이하면 무제한 유지. */
        private Integer maxInactiveSessions = 0;
    }

    /**
     * 프로바이더 능력치와 Agent 설정을 병합한 최종 세션 설정.
     * 우선순위: Agent override > Provider > 기본값
     */
    public record EffectiveSessionConfig(
            int contextWindow,
            int maxTokens,
            int charsPerToken,
            double compactionRatio,
            int recentKeep
    ) {}

    /**
     * 프로바이더 능력치와 Agent 설정을 병합하여 EffectiveSessionConfig 생성.
     */
    public EffectiveSessionConfig resolveSessionConfig(ProviderCapabilities caps) {
        Integer cw = firstNonNull(session.getContextWindow(), safeGet(caps, ProviderCapabilities::contextWindow));
        if (cw == null) {
            throw new IllegalStateException(
                    "context-window가 설정되지 않았습니다. " +
                    "plugins.agent.session.context-window 또는 프로바이더의 context-window를 설정하세요.");
        }
        int mt = firstNonNull(maxTokens, safeGet(caps, ProviderCapabilities::maxTokens), 4096);
        int cpt = firstNonNull(session.getCharsPerToken(), safeGet(caps, ProviderCapabilities::charsPerToken), 3);
        double cr = session.getCompactionRatio() != null ? session.getCompactionRatio() : 0.5;
        int rk = firstNonNull(session.getRecentKeep(), safeGet(caps, ProviderCapabilities::recentKeep),
                computeDefaultRecentKeep(cw));
        return new EffectiveSessionConfig(cw, mt, cpt, cr, rk);
    }

    private static int computeDefaultRecentKeep(int contextWindow) {
        return Math.max(10, Math.min(contextWindow / 2048, 50));
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static <T> T safeGet(ProviderCapabilities caps, java.util.function.Function<ProviderCapabilities, T> getter) {
        return caps != null ? getter.apply(caps) : null;
    }

    @Getter
    @Setter
    public static class ToolsConfig {
        private Map<String, String> policy = new LinkedHashMap<>();
        private String policyDefault = "ask";
        private List<String> globalDeny = new ArrayList<>();
        private Map<String, List<String>> groups = new LinkedHashMap<>();
        private Map<String, ContextPolicy> contexts = new LinkedHashMap<>();

        @Getter
        @Setter
        public static class ContextPolicy {
            private String defaultPolicy = "deny";
            private List<String> allow = new ArrayList<>();
            private List<String> escalate = new ArrayList<>();
        }
    }

    @Getter
    @Setter
    public static class ExecConfig {
        private String security = "allowlist";
        private Integer timeoutSeconds = 120;
        private Integer outputLimitKb = 16;
        private List<String> allowlist = List.of(
                "ls", "cat", "head", "tail", "grep", "find", "wc",
                "echo", "pwd", "date", "which", "file", "tree"
        );
        private List<String> blockedPatterns = List.of(
                "rm -rf /", "mkfs", "dd if=", "> /dev/"
        );
    }

    @Getter
    @Setter
    public static class FileConfig {
        private List<String> allowedPaths = List.of();
        private Integer maxFileSizeKb = 512;
        private Integer maxSearchResults = 100;
        private Integer maxSearchDepth = 20;
        /** 스킬 다운로드 최대 크기 (MB). 기본 10MB. */
        private Integer maxSkillDownloadMb = 10;
    }

    @Getter
    @Setter
    public static class WebSearchConfig {
        private String provider = "brave";
        private String apiKey;
        private String searxngUrl;
    }

    @Getter
    @Setter
    public static class WebFetchConfig {
        private Integer maxContentLength = 8000;
        private Integer timeoutSeconds = 15;
    }

    @Getter
    @Setter
    public static class BrowserConfig {
        private Boolean headless = true;
        private Integer timeoutSeconds = 30;
        private Integer maxContentLength = 8000;
        private Integer autoCloseMinutes = 10;
    }

    @Getter
    @Setter
    public static class SubagentConfig {
        private Integer maxConcurrent = 2;
        private Integer timeoutSeconds = 300;
        private List<String> excludedTools = List.of(
                "spawn_subagent"
        );
    }

    @Getter
    @Setter
    public static class CronConfig {
        private Long checkIntervalMs = 30000L;
        private Integer agentTurnTimeoutSeconds = 300;
        private List<String> excludedTools = List.of("spawn_subagent", "cron");
        private RoutineConfig.RoutineScopeConfig scope = new RoutineConfig.RoutineScopeConfig();
    }

    @Getter
    @Setter
    public static class TaskConfig {
        private Integer maxRetry = 5;
        private Integer approvalTimeoutHours = 24;
        private Integer reminderCount = 1;
    }

    @Getter
    @Setter
    public static class SuggestConfig {
        private Boolean enabled = true;         // 자율 제안 활성화
        private Integer cooldownHours = 3;
        private Integer dailyLimit = 3;
        private Integer activeStartHour = 8;   // KST
        private Integer activeEndHour = 22;     // KST
        private Integer dailyTaskLimit = 2;     // 제안에서 자동 생성할 일일 최대 Task 수
        private Integer taskMaxRetries = 3;     // 자동 생성 Task의 최대 재시도 횟수
        private Integer approvalTimeoutMinutes = 30;  // 승인 대기 타임아웃 (분)
        private Double explorationRate = 0.2;          // ε-greedy 탐색 비율 (Phase 1)
        private Double curiosityThreshold = 0.3;       // Curiosity Score 임계값 (Phase 1)
    }

    @Getter
    @Setter
    public static class RetrospectConfig {
        private Boolean enabled = true;
        /** 세션 회고 최소 메시지 수 */
        private Integer minMessagesForSession = 4;
        /** Task 회고 활성화 */
        private Boolean taskEnabled = true;
    }

    @Getter
    @Setter
    public static class RoutineConfig {
        private Boolean enabled = false;
        private Long intervalMs = 300000L;       // 5분
        private Integer activeStartHour = 7;    // KST — 디스코드 노티 시간대
        private Integer activeEndHour = 1;      // KST — 디스코드 노티 시간대 (자정 넘김)
        private Integer activeWorkMinutes = 15;  // 활성 작업 있을 때 시간예산
        private List<String> excludedTools = List.of("spawn_subagent", "cron");
        private Integer maxConsecutivePartials = 3;
        private RoutineScopeConfig scope;

        @Getter
        @Setter
        public static class RoutineScopeConfig {
            private List<String> allow = List.of();
            private List<String> ask = List.of();
        }
    }

    @Getter
    @Setter
    public static class GeminiConfig {
        private Boolean enabled = false;
        private String cliPath = "gemini";
        private String model = "gemini-2.5-flash";
        private Integer timeoutSeconds = 120;
    }
}
