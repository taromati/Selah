package me.taromati.almah.setup;

import me.taromati.almah.agent.config.AgentConfigDefaults;
import me.taromati.almah.memory.config.MemoryConfigDefaults;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML config.yml 생성기 겸 기존 config 파싱기.
 * 기존 config.yml이 있으면 fromExistingConfig()로 읽어서 기본값을 채운다.
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
    private String notificationChannel = "agent";
    private String agentLlmProviderName = null;  // null = llmProvider 사용
    private String agentRoutineProvider = null;   // null = llmProvider 사용
    private String agentSuggestProvider = null;   // null = llmProvider 사용
    private Map<String, String> agentToolsPolicies = agentDefaultToolsPolicies();
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
     * 기존 config.yml을 직접 읽어서 모든 값을 채운 ConfigGenerator를 생성한다.
     * 파일이 없으면 빈 기본값의 ConfigGenerator를 반환한다.
     * 이후 변경할 필드만 setter로 덮어쓰면 된다.
     */
    public static ConfigGenerator fromExistingConfig(Path configPath) {
        String content;
        try {
            content = Files.readString(configPath);
        } catch (IOException e) {
            return new ConfigGenerator();
        }
        return fromConfigContent(content);
    }

    /**
     * config.yml 문자열을 파싱하여 ConfigGenerator를 생성한다.
     */
    @SuppressWarnings("unchecked")
    static ConfigGenerator fromConfigContent(String content) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(content);
        if (root == null) root = Map.of();

        ConfigGenerator gen = new ConfigGenerator();

        // ── Discord ──
        boolean discEnabled = YamlHelper.getBool(root, false, "discord", "enabled");
        String discToken = YamlHelper.getString(root, "discord", "token");
        String discServerName = YamlHelper.getString(root, "discord", "server-name");
        // discord.enabled가 없지만 토큰이 있으면 활성으로 간주 (하위호환)
        if (!discEnabled && discToken != null && !discToken.isEmpty()) {
            discEnabled = true;
        }
        gen.discordEnabled = discEnabled;
        gen.discordToken = nullToEmpty(discToken);
        gen.serverName = nullToEmpty(discServerName);

        // ── Telegram ──
        gen.telegramEnabled = YamlHelper.getBool(root, false, "telegram", "enabled");
        gen.telegramToken = nullToEmpty(YamlHelper.getString(root, "telegram", "token"));
        gen.telegramBotUsername = nullToEmpty(YamlHelper.getString(root, "telegram", "bot-username"));
        // channel-mappings: 동적으로 모든 키 읽기
        Map<String, String> mappings = new LinkedHashMap<>();
        Object rawMappings = YamlHelper.getPath(root, "telegram", "channel-mappings");
        if (rawMappings instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    mappings.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
        }
        gen.telegramChannelMappings = mappings;

        // ── LLM ──
        String provider = detectLlmProvider(root);
        String providerKey = provider != null ? provider : "openai";
        gen.llmProvider = providerKey;
        gen.llmApiKey = nullToEmpty(YamlHelper.getString(root, "llm", "providers", providerKey, "api-key"));
        gen.llmBaseUrl = nullToEmpty(YamlHelper.getString(root, "llm", "providers", providerKey, "base-url"));
        gen.llmModel = nullToEmpty(YamlHelper.getString(root, "llm", "providers", providerKey, "model"));
        gen.llmCliPath = nullToEmpty(YamlHelper.getString(root, "llm", "providers", providerKey, "cli-path"));
        gen.contextWindow = YamlHelper.getInt(root, 0, "llm", "providers", providerKey, "context-window");

        // ── Embedding ──
        gen.embeddingProvider = nullToEmpty(YamlHelper.getString(root, "llm", "embedding", "provider"));
        gen.embeddingBaseUrl = nullToEmpty(YamlHelper.getString(root, "llm", "embedding", "base-url"));
        gen.embeddingApiKey = nullToEmpty(YamlHelper.getString(root, "llm", "embedding", "api-key"));
        gen.embeddingModel = nullToEmpty(YamlHelper.getString(root, "llm", "embedding", "model"));
        gen.embeddingDimensions = YamlHelper.getInt(root, 0, "llm", "embedding", "dimensions");

        // ── Agent ──
        gen.agentEnabled = YamlHelper.getBool(root, true, "plugins", "agent", "enabled");
        String channelName = YamlHelper.getString(root, "plugins", "agent", "channel-name");
        gen.agentChannelName = channelName != null ? channelName : "agent";
        String dataDir = YamlHelper.getString(root, "plugins", "agent", "data-dir");
        gen.agentDataDir = dataDir != null ? dataDir : "./agent-data/";
        String notifChannel = YamlHelper.getString(root, "plugins", "notification-channel");
        gen.notificationChannel = notifChannel != null ? notifChannel : "agent";

        // Agent LLM Provider Name (llm-provider-name 우선, llm-provider 폴백)
        String agentLlmName = YamlHelper.getString(root, "plugins", "agent", "llm-provider-name");
        if (agentLlmName == null) {
            agentLlmName = YamlHelper.getString(root, "plugins", "agent", "llm-provider");
        }
        gen.agentLlmProviderName = agentLlmName;
        gen.agentRoutineProvider = YamlHelper.getString(root, "plugins", "agent", "routine-provider");
        gen.agentSuggestProvider = YamlHelper.getString(root, "plugins", "agent", "suggest-provider");

        // Agent Tools Policies
        Map<String, String> toolsPolicies = new LinkedHashMap<>();
        Map<String, Object> policyMap = YamlHelper.getMap(root, "plugins", "agent", "tools", "policy");
        for (var entry : policyMap.entrySet()) {
            if (entry.getValue() != null) {
                toolsPolicies.put(entry.getKey(), entry.getValue().toString());
            }
        }
        if (!toolsPolicies.isEmpty()) {
            gen.agentToolsPolicies = toolsPolicies;
        }
        String toolsPolicyDefault = YamlHelper.getString(root, "plugins", "agent", "tools", "policy-default");
        if (toolsPolicyDefault != null) {
            gen.agentToolsPolicyDefault = toolsPolicyDefault;
        }

        // Agent Suggest
        gen.agentSuggestEnabled = YamlHelper.getBool(root, false, "plugins", "agent", "suggest", "enabled");

        // Agent Datasource
        gen.agentDatasourceUrl = YamlHelper.getString(root, "plugins", "agent", "datasource", "url");
        String driverClassName = YamlHelper.getString(root, "plugins", "agent", "datasource", "driver-class-name");
        if (driverClassName != null) {
            gen.agentDatasourceDriverClassName = driverClassName;
        }

        // Web Search
        String webSearchProv = YamlHelper.getString(root, "plugins", "agent", "web-search", "provider");
        if (webSearchProv != null) {
            gen.webSearchProvider = webSearchProv;
        }
        gen.searxngUrl = nullToEmpty(YamlHelper.getString(root, "plugins", "agent", "web-search", "searxng-url"));

        // Web Auth
        gen.authEnabled = YamlHelper.getBool(root, false, "web", "auth", "enabled");

        return gen;
    }

    // ── LLM 프로바이더 감지 ──

    private static String detectLlmProvider(Map<String, Object> root) {
        Map<String, Object> providers = YamlHelper.getMap(root, "llm", "providers");
        for (var entry : providers.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> providerMap) {
                Object enabled = providerMap.get("enabled");
                if (Boolean.TRUE.equals(enabled) || "true".equals(enabled)) {
                    return entry.getKey();
                }
            }
        }
        return null;
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

    // ── Getters (읽기 전용 — 웹 UI, Doctor, Status 등에서 사용) ──

    public boolean hasDiscord() {
        return discordToken != null && !discordToken.isEmpty()
                && !discordToken.startsWith("YOUR_");
    }

    public boolean hasTelegram() {
        return telegramEnabled && telegramToken != null
                && !telegramToken.isEmpty() && !telegramToken.startsWith("YOUR_");
    }

    public boolean hasAnyMessenger() { return hasDiscord() || hasTelegram(); }

    public boolean getDiscordEnabled() { return discordEnabled; }
    public String getDiscordToken() { return discordToken; }
    public String getDiscordServerName() { return serverName; }
    public boolean getTelegramEnabled() { return telegramEnabled; }
    public String getTelegramToken() { return telegramToken; }
    public String getTelegramBotUsername() { return telegramBotUsername; }
    public Map<String, String> getTelegramChannelMappings() { return telegramChannelMappings; }

    public String getLlmProvider() { return llmProvider; }
    public String getLlmApiKey() { return llmApiKey; }
    public String getLlmBaseUrl() { return llmBaseUrl; }
    public String getLlmModel() { return llmModel; }
    public String getLlmCliPath() { return llmCliPath; }
    public int getLlmContextWindow() { return contextWindow; }

    public String getEmbeddingProvider() { return embeddingProvider; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public String getEmbeddingModel() { return embeddingModel; }
    public int getEmbeddingDimensions() { return embeddingDimensions; }

    public boolean getAgentEnabled() { return agentEnabled; }
    public String getAgentChannelName() { return agentChannelName; }
    public String getNotificationChannel() { return notificationChannel; }
    public String getAgentLlmProviderName() { return agentLlmProviderName; }
    public String getAgentRoutineProvider() { return agentRoutineProvider; }
    public String getAgentSuggestProvider() { return agentSuggestProvider; }
    public Map<String, String> getAgentToolsPolicies() { return agentToolsPolicies; }
    public String getAgentToolsPolicyDefault() { return agentToolsPolicyDefault; }
    public boolean getAgentSuggestEnabled() { return agentSuggestEnabled; }
    public String getAgentDatasourceUrl() { return agentDatasourceUrl; }
    public String getAgentDatasourceDriverClassName() { return agentDatasourceDriverClassName; }

    public String getWebSearchProvider() { return webSearchProvider; }
    public String getSearxngUrl() { return searxngUrl; }

    public boolean getAuthEnabled() { return authEnabled; }

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
        this.notificationChannel = "agent";
        this.agentLlmProviderName = null;
        this.agentRoutineProvider = null;
        this.agentSuggestProvider = null;
        this.agentToolsPolicies = agentDefaultToolsPolicies();
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

        // ── Plugins: Agent ──
        Map<String, Object> agentDefaults = AgentConfigDefaults.defaults();
        agentDefaults.put("channel-name", agentChannelName);
        agentDefaults.put("data-dir", agentDataDir);
        agentDefaults.put("llm-provider-name", effectiveLlmProviderName);
        agentDefaults.put("routine-provider", effectiveRoutineProvider);
        agentDefaults.put("suggest-provider", effectiveSuggestProvider);

        // tools: 사용자 입력 policy 병합
        @SuppressWarnings("unchecked")
        Map<String, Object> toolsMap = (Map<String, Object>) agentDefaults.get("tools");
        if (!agentToolsPolicies.isEmpty()) {
            toolsMap.put("policy", new LinkedHashMap<>(agentToolsPolicies));
        }
        toolsMap.put("policy-default", agentToolsPolicyDefault);

        // suggest
        @SuppressWarnings("unchecked")
        Map<String, Object> suggestMap = (Map<String, Object>) agentDefaults.get("suggest");
        suggestMap.put("enabled", agentSuggestEnabled);

        // datasource
        @SuppressWarnings("unchecked")
        Map<String, Object> dsMap = (Map<String, Object>) agentDefaults.get("datasource");
        dsMap.put("url", effectiveDatasourceUrl);

        // web-search
        @SuppressWarnings("unchecked")
        Map<String, Object> wsMap = (Map<String, Object>) agentDefaults.get("web-search");
        wsMap.put("provider", webSearchProvider);
        if ("searxng".equals(webSearchProvider) && !searxngUrl.isEmpty()) {
            wsMap.put("searxng-url", searxngUrl);
        }

        // ── Plugins: Memory ──
        Map<String, Object> memoryDefaults = MemoryConfigDefaults.defaults();
        memoryDefaults.put("llm-provider", effectiveLlmProviderName);

        // ── 조립 ──
        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("notification-channel", agentChannelName);
        plugins.put("agent", agentDefaults);
        plugins.put("memory", memoryDefaults);

        sb.append("\n");
        sb.append("# ── Plugins ──\n");
        sb.append(yamlDump("plugins", plugins));

        // Web Auth
        sb.append("\n");
        sb.append("# ── Web Auth ──\n");
        sb.append(yamlDump("web", Map.of("auth", Map.of("enabled", authEnabled))));

        // Memory Engine
        sb.append("\n");
        sb.append("# ── Memory Engine ──\n");
        sb.append(yamlDump("memory-engine", MemoryConfigDefaults.memoryEngineDefaults()));

        return sb.toString();
    }

    private String yamlDump(String rootKey, Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(rootKey, value);
        return yaml.dump(wrapper);
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

    @SuppressWarnings("unchecked")
    private static Map<String, String> agentDefaultToolsPolicies() {
        Map<String, Object> tools = (Map<String, Object>) AgentConfigDefaults.defaults().get("tools");
        Map<String, String> policy = (Map<String, String>) tools.get("policy");
        return new LinkedHashMap<>(policy);
    }
}
