package me.taromati.almah.setup;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config.yml의 모든 섹션을 SnakeYAML로 파싱한다.
 * ExistingConfig를 composition으로 포함하고 나머지 섹션을 파싱한다.
 */
public class FullConfig {

    private final ExistingConfig messenger;

    // LLM
    private final String llmProvider;   // "openai-codex" | "openai" | "vllm" | "gemini-cli"
    private final String llmApiKey;
    private final String llmBaseUrl;
    private final String llmModel;
    private final String llmCliPath;    // Gemini CLI 경로 (gemini-cli 전용)
    private final int llmContextWindow;

    // Embedding
    private final String embeddingProvider;  // null/""(onnx) | "http"
    private final String embeddingBaseUrl;
    private final String embeddingApiKey;
    private final String embeddingModel;
    private final int embeddingDimensions;

    // Agent
    private final boolean agentEnabled;
    private final String agentChannelName;
    private final String agentDataDir;
    private final String notificationChannel;
    private final String agentLlmProviderName;
    private final String agentRoutineProvider;
    private final String agentSuggestProvider;
    private final Map<String, String> agentToolsPolicies;
    private final String agentToolsPolicyDefault;
    private final boolean agentSuggestEnabled;
    private final String agentDatasourceUrl;
    private final String agentDatasourceDriverClassName;

    // Web Search
    private final String webSearchProvider;
    private final String searxngUrl;

    // Web Auth
    private final boolean authEnabled;

    FullConfig(ExistingConfig messenger,
               String llmProvider, String llmApiKey, String llmBaseUrl, String llmModel, String llmCliPath,
               int llmContextWindow,
               String embeddingProvider, String embeddingBaseUrl, String embeddingApiKey, String embeddingModel, int embeddingDimensions,
               boolean agentEnabled, String agentChannelName, String agentDataDir, String notificationChannel,
               String agentLlmProviderName, String agentRoutineProvider, String agentSuggestProvider,
               Map<String, String> agentToolsPolicies, String agentToolsPolicyDefault,
               boolean agentSuggestEnabled, String agentDatasourceUrl, String agentDatasourceDriverClassName,
               String webSearchProvider, String searxngUrl, boolean authEnabled) {
        this.messenger = messenger;
        this.llmProvider = llmProvider;
        this.llmApiKey = llmApiKey;
        this.llmBaseUrl = llmBaseUrl;
        this.llmModel = llmModel;
        this.llmCliPath = llmCliPath;
        this.llmContextWindow = llmContextWindow;
        this.embeddingProvider = embeddingProvider;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingApiKey = embeddingApiKey;
        this.embeddingModel = embeddingModel;
        this.embeddingDimensions = embeddingDimensions;
        this.agentEnabled = agentEnabled;
        this.agentChannelName = agentChannelName;
        this.agentDataDir = agentDataDir;
        this.notificationChannel = notificationChannel;
        this.agentLlmProviderName = agentLlmProviderName;
        this.agentRoutineProvider = agentRoutineProvider;
        this.agentSuggestProvider = agentSuggestProvider;
        this.agentToolsPolicies = agentToolsPolicies;
        this.agentToolsPolicyDefault = agentToolsPolicyDefault;
        this.agentSuggestEnabled = agentSuggestEnabled;
        this.agentDatasourceUrl = agentDatasourceUrl;
        this.agentDatasourceDriverClassName = agentDatasourceDriverClassName;
        this.webSearchProvider = webSearchProvider;
        this.searxngUrl = searxngUrl;
        this.authEnabled = authEnabled;
    }

    public static FullConfig parse(Path configPath) {
        String content;
        try {
            content = Files.readString(configPath);
        } catch (IOException e) {
            return FullConfig.empty();
        }
        return parseContent(content);
    }

    public static FullConfig parseContent(String content) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(content);
        if (root == null) root = Map.of();

        ExistingConfig messenger = ExistingConfig.fromMap(root);

        // LLM 프로바이더 감지: providers 하위에서 enabled: true인 항목
        String llmProvider = detectLlmProvider(root);
        String providerKey = llmProvider != null ? llmProvider : "openai";

        String llmApiKey = YamlHelper.getString(root, "llm", "providers", providerKey, "api-key");
        String llmBaseUrl = YamlHelper.getString(root, "llm", "providers", providerKey, "base-url");
        String llmModel = YamlHelper.getString(root, "llm", "providers", providerKey, "model");
        String llmCliPath = YamlHelper.getString(root, "llm", "providers", providerKey, "cli-path");
        int llmContextWindow = YamlHelper.getInt(root, 0, "llm", "providers", providerKey, "context-window");

        // Embedding
        String embProvider = YamlHelper.getString(root, "llm", "embedding", "provider");
        String embBaseUrl = YamlHelper.getString(root, "llm", "embedding", "base-url");
        String embApiKey = YamlHelper.getString(root, "llm", "embedding", "api-key");
        String embModel = YamlHelper.getString(root, "llm", "embedding", "model");
        int embDimensions = YamlHelper.getInt(root, 0, "llm", "embedding", "dimensions");

        // Agent
        boolean agentEnabled = YamlHelper.getBool(root, true, "plugins", "agent", "enabled");
        String channelName = YamlHelper.getString(root, "plugins", "agent", "channel-name");
        String dataDir = YamlHelper.getString(root, "plugins", "agent", "data-dir");
        String notifChannel = YamlHelper.getString(root, "plugins", "notification-channel");

        // Agent LLM Provider Name (llm-provider-name 우선, llm-provider 폴백)
        String agentLlmProviderName = YamlHelper.getString(root, "plugins", "agent", "llm-provider-name");
        if (agentLlmProviderName == null) {
            agentLlmProviderName = YamlHelper.getString(root, "plugins", "agent", "llm-provider");
        }

        String agentRoutineProvider = YamlHelper.getString(root, "plugins", "agent", "routine-provider");
        String agentSuggestProvider = YamlHelper.getString(root, "plugins", "agent", "suggest-provider");

        // Agent Tools Policies
        Map<String, String> toolsPolicies = new LinkedHashMap<>();
        Map<String, Object> policyMap = YamlHelper.getMap(root, "plugins", "agent", "tools", "policy");
        for (var entry : policyMap.entrySet()) {
            if (entry.getValue() != null) {
                toolsPolicies.put(entry.getKey(), entry.getValue().toString());
            }
        }
        String toolsPolicyDefault = YamlHelper.getString(root, "plugins", "agent", "tools", "policy-default");

        // Agent Suggest
        boolean agentSuggestEnabled = YamlHelper.getBool(root, false, "plugins", "agent", "suggest", "enabled");

        // Agent Datasource
        String datasourceUrl = YamlHelper.getString(root, "plugins", "agent", "datasource", "url");
        String datasourceDriverClassName = YamlHelper.getString(root, "plugins", "agent", "datasource", "driver-class-name");

        // Web Search
        String webSearchProvider = YamlHelper.getString(root, "plugins", "agent", "web-search", "provider");
        String searxng = YamlHelper.getString(root, "plugins", "agent", "web-search", "searxng-url");

        // Web Auth
        boolean authEnabled = YamlHelper.getBool(root, false, "web", "auth", "enabled");

        return new FullConfig(messenger,
                llmProvider, llmApiKey, llmBaseUrl, llmModel, llmCliPath,
                llmContextWindow,
                embProvider, embBaseUrl, embApiKey, embModel, embDimensions,
                agentEnabled, channelName, dataDir, notifChannel,
                agentLlmProviderName, agentRoutineProvider, agentSuggestProvider,
                toolsPolicies, toolsPolicyDefault,
                agentSuggestEnabled, datasourceUrl, datasourceDriverClassName,
                webSearchProvider, searxng, authEnabled);
    }

    /** config.yml 미존재 시 빈 기본값 */
    public static FullConfig empty() {
        return new FullConfig(ExistingConfig.empty(),
                null, null, null, null, null,
                0,
                null, null, null, null, 0,
                true, null, null, null,
                null, null, null,
                Map.of(), null,
                false, null, null,
                null, null, false);
    }

    // ── LLM 프로바이더 감지 ──

    @SuppressWarnings("unchecked")
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

    // ── Messenger delegation ──

    public boolean hasDiscord() { return messenger.hasDiscord(); }
    public boolean hasTelegram() { return messenger.hasTelegram(); }
    public boolean hasAnyMessenger() { return messenger.hasAnyMessenger(); }

    public boolean discordEnabled() { return messenger.discordEnabled(); }
    public String discordToken() { return messenger.discordToken(); }
    public String discordServerName() { return messenger.discordServerName(); }
    public boolean telegramEnabled() { return messenger.telegramEnabled(); }
    public String telegramToken() { return messenger.telegramToken(); }
    public String telegramBotUsername() { return messenger.telegramBotUsername(); }
    public Map<String, String> telegramChannelMappings() { return messenger.telegramChannelMappings(); }

    // ── LLM ──

    public String llmProvider() { return llmProvider; }
    public String llmApiKey() { return llmApiKey; }
    public String llmBaseUrl() { return llmBaseUrl; }
    public String llmModel() { return llmModel; }
    public String llmCliPath() { return llmCliPath; }
    public int llmContextWindow() { return llmContextWindow; }

    // ── Embedding ──

    public String embeddingProvider() { return embeddingProvider; }
    public String embeddingBaseUrl() { return embeddingBaseUrl; }
    public String embeddingApiKey() { return embeddingApiKey; }
    public String embeddingModel() { return embeddingModel; }
    public int embeddingDimensions() { return embeddingDimensions; }

    // ── Agent ──

    public boolean agentEnabled() { return agentEnabled; }
    public String agentChannelName() { return agentChannelName; }
    public String agentDataDir() { return agentDataDir; }
    public String notificationChannel() { return notificationChannel; }
    public String agentLlmProviderName() { return agentLlmProviderName; }
    public String agentRoutineProvider() { return agentRoutineProvider; }
    public String agentSuggestProvider() { return agentSuggestProvider; }
    public Map<String, String> agentToolsPolicies() { return agentToolsPolicies; }
    public String agentToolsPolicyDefault() { return agentToolsPolicyDefault; }
    public boolean agentSuggestEnabled() { return agentSuggestEnabled; }
    public String agentDatasourceUrl() { return agentDatasourceUrl; }
    public String agentDatasourceDriverClassName() { return agentDatasourceDriverClassName; }

    // ── Web Search ──

    public String webSearchProvider() { return webSearchProvider; }
    public String searxngUrl() { return searxngUrl; }

    // ── Web Auth ──

    public boolean authEnabled() { return authEnabled; }
}
