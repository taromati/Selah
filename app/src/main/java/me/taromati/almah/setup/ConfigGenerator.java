package me.taromati.almah.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML config.yml 생성기.
 */
public class ConfigGenerator {

    private boolean discordEnabled = false;
    private String discordToken = "";
    private String serverName = "";
    private boolean telegramEnabled = false;
    private String telegramToken = "";
    private String telegramBotUsername = "";
    private Map<String, String> telegramChannelMappings = new LinkedHashMap<>();
    private String llmProvider = "openai";
    private String llmApiKey = "";
    private String llmBaseUrl = "";
    private String llmModel = "";
    private String llmCliPath = "";  // Gemini CLI 경로
    private int contextWindow = 0;   // 0 = 미설정
    private String embeddingProvider = "";  // "" = 기본(onnx), "http" = OpenAI API
    private String embeddingBaseUrl = "";
    private String embeddingApiKey = "";
    private String embeddingModel = "";
    private int embeddingDimensions = 0;  // 0 = 기본값 사용
    private boolean agentEnabled = true;
    private String agentChannelName = "agent";
    private String agentDataDir = "./agent-data/";
    private String notificationChannel = "system";
    private String agentLlmProviderName = null;  // null = llmProvider 사용
    private String agentRoutineProvider = null;   // null = llmProvider 사용
    private String agentSuggestProvider = null;   // null = llmProvider 사용
    private Map<String, String> agentToolsPolicies = defaultToolsPolicies();
    private String agentToolsPolicyDefault = "ask";
    private boolean agentSuggestEnabled = false;
    private String agentDatasourceUrl = null;     // null = agentDataDir 기반 자동 생성
    private String agentDatasourceDriverClassName = "org.sqlite.JDBC";
    private String webSearchProvider = "searxng";
    private String searxngUrl = "http://localhost:8888";
    private boolean authEnabled = false;

    // ── 후처리 플래그 ──

    private boolean onnxSelected = false;
    private boolean copyAgentDefaults = false;

    // ── Factory ──

    /**
     * 기존 FullConfig의 모든 값을 복사하여 ConfigGenerator를 생성한다.
     * 이후 변경할 필드만 setter로 덮어쓰면 된다.
     */
    public static ConfigGenerator fromFullConfig(FullConfig full) {
        ConfigGenerator gen = new ConfigGenerator();

        // Messenger
        gen.discordEnabled = full.discordEnabled();
        gen.discordToken = nullToEmpty(full.discordToken());
        gen.serverName = nullToEmpty(full.discordServerName());
        gen.telegramEnabled = full.telegramEnabled();
        gen.telegramToken = nullToEmpty(full.telegramToken());
        gen.telegramBotUsername = nullToEmpty(full.telegramBotUsername());
        gen.telegramChannelMappings = new LinkedHashMap<>(full.telegramChannelMappings());

        // LLM
        String provider = full.llmProvider();
        gen.llmProvider = provider != null ? provider : "openai";
        gen.llmApiKey = nullToEmpty(full.llmApiKey());
        gen.llmBaseUrl = nullToEmpty(full.llmBaseUrl());
        gen.llmModel = nullToEmpty(full.llmModel());
        gen.llmCliPath = nullToEmpty(full.llmCliPath());
        gen.contextWindow = full.llmContextWindow();

        // Embedding
        gen.embeddingProvider = nullToEmpty(full.embeddingProvider());
        gen.embeddingBaseUrl = nullToEmpty(full.embeddingBaseUrl());
        gen.embeddingApiKey = nullToEmpty(full.embeddingApiKey());
        gen.embeddingModel = nullToEmpty(full.embeddingModel());
        gen.embeddingDimensions = full.embeddingDimensions();

        // Agent
        gen.agentEnabled = full.agentEnabled();
        gen.agentChannelName = full.agentChannelName() != null ? full.agentChannelName() : "agent";
        gen.agentDataDir = full.agentDataDir() != null ? full.agentDataDir() : "./agent-data/";
        gen.notificationChannel = full.notificationChannel() != null ? full.notificationChannel() : "system";
        gen.agentLlmProviderName = full.agentLlmProviderName();
        gen.agentRoutineProvider = full.agentRoutineProvider();
        gen.agentSuggestProvider = full.agentSuggestProvider();

        // Agent Tools
        Map<String, String> policies = full.agentToolsPolicies();
        if (policies != null && !policies.isEmpty()) {
            gen.agentToolsPolicies = new LinkedHashMap<>(policies);
        }
        if (full.agentToolsPolicyDefault() != null) {
            gen.agentToolsPolicyDefault = full.agentToolsPolicyDefault();
        }

        // Agent Suggest
        gen.agentSuggestEnabled = full.agentSuggestEnabled();

        // Agent Datasource
        gen.agentDatasourceUrl = full.agentDatasourceUrl();
        if (full.agentDatasourceDriverClassName() != null) {
            gen.agentDatasourceDriverClassName = full.agentDatasourceDriverClassName();
        }

        // Web Search
        if (full.webSearchProvider() != null) {
            gen.webSearchProvider = full.webSearchProvider();
        }
        gen.searxngUrl = nullToEmpty(full.searxngUrl());

        // Web Auth
        gen.authEnabled = full.authEnabled();

        return gen;
    }

    // ── Setters ──

    public ConfigGenerator discordEnabled(boolean enabled) { this.discordEnabled = enabled; return this; }
    public ConfigGenerator discordToken(String token) {
        this.discordToken = token;
        this.discordEnabled = true;
        return this;
    }
    public ConfigGenerator serverName(String name) { this.serverName = name; return this; }
    public ConfigGenerator telegramEnabled(boolean enabled) { this.telegramEnabled = enabled; return this; }
    public ConfigGenerator telegramToken(String token) { this.telegramToken = token; return this; }
    public ConfigGenerator telegramBotUsername(String username) { this.telegramBotUsername = username; return this; }
    public ConfigGenerator telegramChannelMapping(String name, String chatId) {
        this.telegramChannelMappings.put(name, chatId);
        return this;
    }
    public ConfigGenerator clearTelegramChannelMappings() {
        this.telegramChannelMappings = new LinkedHashMap<>();
        return this;
    }
    public ConfigGenerator llmProvider(String provider) { this.llmProvider = provider; return this; }
    public ConfigGenerator llmApiKey(String key) { this.llmApiKey = key; return this; }
    public ConfigGenerator llmBaseUrl(String url) { this.llmBaseUrl = url; return this; }
    public ConfigGenerator llmModel(String model) { this.llmModel = model; return this; }
    public ConfigGenerator llmCliPath(String path) { this.llmCliPath = path; return this; }
    public ConfigGenerator contextWindow(int contextWindow) { this.contextWindow = contextWindow; return this; }
    public ConfigGenerator embeddingProvider(String provider) { this.embeddingProvider = provider; return this; }
    public ConfigGenerator embeddingBaseUrl(String url) { this.embeddingBaseUrl = url; return this; }
    public ConfigGenerator embeddingApiKey(String key) { this.embeddingApiKey = key; return this; }
    public ConfigGenerator embeddingModel(String model) { this.embeddingModel = model; return this; }
    public ConfigGenerator embeddingDimensions(int dimensions) { this.embeddingDimensions = dimensions; return this; }
    public ConfigGenerator agentEnabled(boolean enabled) { this.agentEnabled = enabled; return this; }
    public ConfigGenerator agentChannelName(String name) { this.agentChannelName = name; return this; }
    public ConfigGenerator agentDataDir(String dir) { this.agentDataDir = dir; return this; }
    public ConfigGenerator notificationChannel(String channel) { this.notificationChannel = channel; return this; }
    public ConfigGenerator agentLlmProviderName(String name) { this.agentLlmProviderName = name; return this; }
    public ConfigGenerator agentRoutineProvider(String provider) { this.agentRoutineProvider = provider; return this; }
    public ConfigGenerator agentSuggestProvider(String provider) { this.agentSuggestProvider = provider; return this; }
    public ConfigGenerator agentToolsPolicies(Map<String, String> policies) {
        this.agentToolsPolicies = new LinkedHashMap<>(policies);
        return this;
    }
    public ConfigGenerator agentToolsPolicyDefault(String policyDefault) { this.agentToolsPolicyDefault = policyDefault; return this; }
    public ConfigGenerator agentSuggestEnabled(boolean enabled) { this.agentSuggestEnabled = enabled; return this; }
    public ConfigGenerator agentDatasourceUrl(String url) { this.agentDatasourceUrl = url; return this; }
    public ConfigGenerator agentDatasourceDriverClassName(String className) { this.agentDatasourceDriverClassName = className; return this; }
    public ConfigGenerator webSearchProvider(String provider) { this.webSearchProvider = provider; return this; }
    public ConfigGenerator searxngUrl(String url) { this.searxngUrl = url; return this; }
    public ConfigGenerator authEnabled(boolean enabled) { this.authEnabled = enabled; return this; }
    public ConfigGenerator onnxSelected(boolean selected) { this.onnxSelected = selected; return this; }
    public boolean isOnnxSelected() { return onnxSelected; }
    public ConfigGenerator copyAgentDefaults(boolean copy) { this.copyAgentDefaults = copy; return this; }
    public boolean isCopyAgentDefaults() { return copyAgentDefaults; }
    public String getAgentDataDir() { return agentDataDir; }
    public boolean isDiscordEnabled() { return discordEnabled; }

    // ── Reset (이전 단계 복귀 시 사용) ──

    public ConfigGenerator resetMessenger() {
        this.discordEnabled = false;
        this.discordToken = "";
        this.serverName = "";
        this.telegramEnabled = false;
        this.telegramToken = "";
        this.telegramBotUsername = "";
        this.telegramChannelMappings = new LinkedHashMap<>();
        return this;
    }

    public ConfigGenerator resetLlmProvider() {
        this.llmProvider = "openai";
        return this;
    }

    public ConfigGenerator resetLlmConfig() {
        this.llmApiKey = "";
        this.llmBaseUrl = "";
        this.llmModel = "";
        this.llmCliPath = "";
        this.contextWindow = 0;
        return this;
    }

    public ConfigGenerator resetLlm() {
        resetLlmProvider();
        return resetLlmConfig();
    }

    public ConfigGenerator resetEmbedding() {
        this.embeddingProvider = "";
        this.embeddingBaseUrl = "";
        this.embeddingApiKey = "";
        this.embeddingModel = "";
        this.embeddingDimensions = 0;
        this.onnxSelected = false;
        return this;
    }

    public ConfigGenerator resetAgent() {
        this.agentChannelName = "agent";
        this.agentDataDir = "./agent-data/";
        this.notificationChannel = "system";
        this.agentLlmProviderName = null;
        this.agentRoutineProvider = null;
        this.agentSuggestProvider = null;
        this.agentToolsPolicies = defaultToolsPolicies();
        this.agentToolsPolicyDefault = "ask";
        this.agentSuggestEnabled = false;
        this.agentDatasourceUrl = null;
        this.agentDatasourceDriverClassName = "org.sqlite.JDBC";
        this.webSearchProvider = "searxng";
        this.searxngUrl = "http://localhost:8888";
        this.copyAgentDefaults = false;
        return this;
    }

    // ── Generation ──

    public void generate(Path outputPath) throws IOException {
        Files.writeString(outputPath, generateString());
    }

    public String generateString() {
        StringBuilder sb = new StringBuilder();

        // Discord 섹션 (토큰이 있으면 출력, enabled 플래그로 제어)
        if (!discordToken.isEmpty()) {
            sb.append("# ── Discord ──\n");
            sb.append("discord:\n");
            sb.append("  enabled: ").append(discordEnabled).append("\n");
            sb.append("  token: ").append(quote(discordToken)).append("\n");
            sb.append("  server-name: ").append(quote(serverName)).append("\n");
            sb.append("\n");
        }

        // Telegram 섹션 (토큰이 있으면 출력, enabled 플래그로 제어)
        if (!telegramToken.isEmpty()) {
            sb.append("# ── Telegram ──\n");
            sb.append("telegram:\n");
            sb.append("  enabled: ").append(telegramEnabled).append("\n");
            sb.append("  token: ").append(quote(telegramToken)).append("\n");
            sb.append("  bot-username: ").append(quote(telegramBotUsername)).append("\n");
            if (!telegramChannelMappings.isEmpty()) {
                sb.append("  channel-mappings:\n");
                for (var entry : telegramChannelMappings.entrySet()) {
                    sb.append("    ").append(entry.getKey()).append(": ")
                            .append(quote(entry.getValue())).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("# ── LLM Providers ──\n");
        sb.append("llm:\n");
        sb.append("  providers:\n");

        switch (llmProvider) {
            case "openai" -> {
                sb.append("    openai:\n");
                sb.append("      type: openai\n");
                sb.append("      enabled: true\n");
                sb.append("      base-url: \"https://api.openai.com/v1\"\n");
                sb.append("      api-key: ").append(quote(llmApiKey)).append("\n");
                sb.append("      model: ").append(quote(llmModel.isEmpty() ? "gpt-5.4" : llmModel)).append("\n");
                if (contextWindow > 0) sb.append("      context-window: ").append(contextWindow).append("\n");
            }
            case "vllm" -> {
                sb.append("    vllm:\n");
                sb.append("      type: vllm\n");
                sb.append("      enabled: true\n");
                sb.append("      base-url: ").append(quote(llmBaseUrl)).append("\n");
                sb.append("      model: ").append(quote(llmModel)).append("\n");
                if (contextWindow > 0) sb.append("      context-window: ").append(contextWindow).append("\n");
            }
            case "gemini-cli" -> {
                sb.append("    gemini-cli:\n");
                sb.append("      type: gemini\n");
                sb.append("      enabled: true\n");
                sb.append("      cli-path: ").append(quote(llmCliPath.isEmpty() ? "gemini" : llmCliPath)).append("\n");
                sb.append("      model: ").append(quote(llmModel.isEmpty() ? "gemini-2.5-pro" : llmModel)).append("\n");
                if (contextWindow > 0) sb.append("      context-window: ").append(contextWindow).append("\n");
            }
            case "openai-codex" -> {
                sb.append("    openai-codex:\n");
                sb.append("      type: openai-codex\n");
                sb.append("      enabled: true\n");
                sb.append("      model: ").append(quote(llmModel.isEmpty() ? "gpt-5.4" : llmModel)).append("\n");
                if (contextWindow > 0) sb.append("      context-window: ").append(contextWindow).append("\n");
            }
        }

        if ("http".equals(embeddingProvider)) {
            sb.append("\n");
            sb.append("  embedding:\n");
            sb.append("    provider: \"http\"\n");
            sb.append("    base-url: ").append(quote(embeddingBaseUrl)).append("\n");
            if (!embeddingApiKey.isEmpty()) {
                sb.append("    api-key: ").append(quote(embeddingApiKey)).append("\n");
            }
            sb.append("    model: ").append(quote(embeddingModel)).append("\n");
            if (embeddingDimensions > 0) {
                sb.append("    dimensions: ").append(embeddingDimensions).append("\n");
            }
        }
        // ONNX(기본값): embedding 섹션 생략 — provider 미설정 시 자동으로 onnx 사용

        String effectiveLlmProviderName = agentLlmProviderName != null ? agentLlmProviderName : llmProvider;
        String effectiveRoutineProvider = agentRoutineProvider != null ? agentRoutineProvider : llmProvider;
        String effectiveSuggestProvider = agentSuggestProvider != null ? agentSuggestProvider : llmProvider;
        String effectiveDatasourceUrl = agentDatasourceUrl != null
                ? agentDatasourceUrl
                : "jdbc:sqlite:" + agentDataDir + "agent.sqlite";

        sb.append("\n");
        sb.append("# ── Plugins ──\n");
        sb.append("plugins:\n");
        sb.append("  notification-channel: ").append(quote(notificationChannel)).append("\n");
        sb.append("\n");
        sb.append("  agent:\n");
        sb.append("    enabled: ").append(agentEnabled).append("\n");
        sb.append("    channel-name: ").append(quote(agentChannelName)).append("\n");
        sb.append("    data-dir: ").append(quote(agentDataDir)).append("\n");
        sb.append("    llm-provider-name: ").append(quote(effectiveLlmProviderName)).append("\n");
        sb.append("    routine-provider: ").append(quote(effectiveRoutineProvider)).append("\n");
        sb.append("    suggest-provider: ").append(quote(effectiveSuggestProvider)).append("\n");
        sb.append("    tools:\n");
        sb.append("      policy:\n");
        for (var entry : agentToolsPolicies.entrySet()) {
            sb.append("        ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        sb.append("      policy-default: ").append(agentToolsPolicyDefault).append("\n");
        sb.append("    suggest:\n");
        sb.append("      enabled: ").append(agentSuggestEnabled).append("\n");
        sb.append("    datasource:\n");
        sb.append("      url: ").append(quote(effectiveDatasourceUrl)).append("\n");
        sb.append("      driver-class-name: ").append(quote(agentDatasourceDriverClassName)).append("\n");

        if (!searxngUrl.isEmpty()) {
            sb.append("    web-search:\n");
            sb.append("      provider: ").append(quote(webSearchProvider)).append("\n");
            if ("searxng".equals(webSearchProvider)) {
                sb.append("      searxng-url: ").append(quote(searxngUrl)).append("\n");
            }
        }

        sb.append("\n");
        sb.append("# ── Web Auth ──\n");
        sb.append("web:\n");
        sb.append("  auth:\n");
        sb.append("    enabled: ").append(authEnabled).append("\n");

        return sb.toString();
    }

    String quote(String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        if (value.contains("\"") || value.contains(":") || value.contains("#") || value.contains("{")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return "\"" + value + "\"";
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static Map<String, String> defaultToolsPolicies() {
        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("web_search", "allow");
        policies.put("web_fetch", "allow");
        policies.put("glob", "allow");
        policies.put("grep", "allow");
        policies.put("file_read", "allow");
        policies.put("memory_search", "allow");
        policies.put("memory_store", "allow");
        policies.put("memory_get", "allow");
        policies.put("memory_explore", "allow");
        policies.put("memory_query", "allow");
        policies.put("skill", "allow");
        policies.put("cron", "allow");
        policies.put("mcp_tools_load", "allow");
        policies.put("exec", "ask");
        policies.put("file_write", "ask");
        policies.put("edit", "ask");
        policies.put("spawn_subagent", "ask");
        return policies;
    }
}
