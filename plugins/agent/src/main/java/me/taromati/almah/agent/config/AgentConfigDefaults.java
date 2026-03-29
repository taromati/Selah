package me.taromati.almah.agent.config;

import me.taromati.almah.core.config.PluginConfigDefaults;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 플러그인 기본 설정.
 * ConfigGenerator가 직접 호출하므로 Spring 컨텍스트 없이 동작해야 한다.
 */
public class AgentConfigDefaults implements PluginConfigDefaults {

    @Override
    public Map<String, Object> getDefaultConfig() {
        return defaults();
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> cfg = new LinkedHashMap<>();

        cfg.put("enabled", true);
        cfg.put("channel-name", "agent");
        cfg.put("data-dir", "./agent-data/");
        cfg.put("system-prompt", "당신은 한국어로 대화하는 AI 에이전트입니다.");
        cfg.put("max-context-messages", 30);
        cfg.put("max-tokens", 4096);
        cfg.put("temperature", 0.7);

        // tools
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("policy-default", "ask");

        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("web_search", "allow");
        policy.put("web_fetch", "allow");
        policy.put("glob", "allow");
        policy.put("grep", "allow");
        policy.put("file_read", "allow");
        policy.put("memory_search", "allow");
        policy.put("memory_store", "allow");
        policy.put("memory_get", "allow");
        policy.put("memory_explore", "allow");
        policy.put("memory_query", "allow");
        policy.put("skill", "allow");
        policy.put("cron", "allow");
        policy.put("mcp_tools_load", "allow");
        policy.put("exec", "ask");
        policy.put("file_write", "ask");
        policy.put("edit", "ask");
        policy.put("spawn_subagent", "ask");
        tools.put("policy", policy);

        Map<String, Object> chatContext = new LinkedHashMap<>();
        chatContext.put("default-policy", "ask");
        chatContext.put("allow", List.of("*"));
        Map<String, Object> contexts = new LinkedHashMap<>();
        contexts.put("chat", chatContext);
        tools.put("contexts", contexts);

        cfg.put("tools", tools);

        // exec
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("security", "allowlist");
        exec.put("timeout-seconds", 120);
        exec.put("output-limit-kb", 16);
        cfg.put("exec", exec);

        // file
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("max-file-size-kb", 512);
        file.put("max-search-results", 100);
        file.put("max-search-depth", 20);
        cfg.put("file", file);

        // web-search
        Map<String, Object> webSearch = new LinkedHashMap<>();
        webSearch.put("provider", "searxng");
        cfg.put("web-search", webSearch);

        // web-fetch
        Map<String, Object> webFetch = new LinkedHashMap<>();
        webFetch.put("max-content-length", 8000);
        webFetch.put("timeout-seconds", 15);
        cfg.put("web-fetch", webFetch);

        // browser
        Map<String, Object> browser = new LinkedHashMap<>();
        browser.put("headless", true);
        browser.put("timeout-seconds", 30);
        browser.put("max-content-length", 8000);
        browser.put("auto-close-minutes", 10);
        cfg.put("browser", browser);

        // session
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("task-idle-timeout-minutes", 60);
        session.put("session-idle-timeout-minutes", 240);
        session.put("max-inactive-sessions", 0);
        cfg.put("session", session);

        // subagent
        Map<String, Object> subagent = new LinkedHashMap<>();
        subagent.put("max-concurrent", 2);
        subagent.put("timeout-seconds", 300);
        cfg.put("subagent", subagent);

        // cron
        Map<String, Object> cron = new LinkedHashMap<>();
        cron.put("check-interval-ms", 30000);
        cron.put("agent-turn-timeout-seconds", 300);
        cfg.put("cron", cron);

        // routine
        Map<String, Object> routine = new LinkedHashMap<>();
        routine.put("enabled", false);
        routine.put("interval-ms", 300000);
        cfg.put("routine", routine);

        // suggest
        Map<String, Object> suggest = new LinkedHashMap<>();
        suggest.put("enabled", false);
        cfg.put("suggest", suggest);

        // datasource
        Map<String, Object> datasource = new LinkedHashMap<>();
        datasource.put("driver-class-name", "org.sqlite.JDBC");
        cfg.put("datasource", datasource);

        return cfg;
    }
}
