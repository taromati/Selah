package me.taromati.almah.llm.config;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.util.LoginShellProcess;
import me.taromati.almah.llm.client.*;
import me.taromati.almah.llm.client.codex.CodexTokenManager;
import me.taromati.almah.llm.client.codex.OpenAiCodexClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Configuration
public class LlmClientConfig {

    @Autowired(required = false) LlmAlertCallback alertCallback;
    @Autowired(required = false) LoginShellProcess loginShellProcess;

    @Bean
    public LlmClientRegistrar llmClientRegistrar(LlmConfigProperties config,
                                                   ConfigurableListableBeanFactory beanFactory) {
        Map<String, LlmClient> resolved = new LinkedHashMap<>();

        // 공개 프로바이더 등록
        for (var entry : config.getProviders().entrySet()) {
            String name = entry.getKey();
            var pc = entry.getValue();
            if (!pc.isEnabled()) continue;

            String type = resolveType(name, pc);
            LlmClient client = switch (type) {
                case "vllm" -> createVllmClient(name, pc);
                case "openai" -> createOpenAiClient(name, pc);
                case "gemini" -> createGeminiClient(name, pc);
                case "openai-codex" -> createCodexClient(name, pc, config, beanFactory);
                default -> {
                    log.debug("[LlmClientConfig] Unknown type '{}' for provider '{}', skipping", type, name);
                    yield null;
                }
            };

            if (client != null) {
                beanFactory.registerSingleton("llmClient_" + name, client);
                resolved.put(name, client);
            }
        }

        var registrar = new LlmClientRegistrar();
        resolved.forEach(registrar::register);
        return registrar;
    }

    private String resolveType(String name, LlmConfigProperties.ProviderConfig pc) {
        if (pc.getType() != null) return pc.getType();
        throw new IllegalStateException(
                "프로바이더 '" + name + "'에 type이 지정되지 않았습니다. " +
                "지원 타입: vllm, openai, gemini, openai-codex");
    }

    private OpenAiCodexClient createCodexClient(String name, LlmConfigProperties.ProviderConfig pc,
                                                  LlmConfigProperties config,
                                                  ConfigurableListableBeanFactory beanFactory) {
        CodexTokenManager tokenManager = new CodexTokenManager(
                pc.getAccessToken(), pc.getRefreshToken(), pc.getAccountId(),
                config.getTokenRefreshMarginSeconds());
        beanFactory.registerSingleton("codexTokenManager_" + name, tokenManager);
        OpenAiCodexClient client = new OpenAiCodexClient(tokenManager, pc);
        if (alertCallback != null) client.setAlertCallback(alertCallback);
        return client;
    }

    private VllmClient createVllmClient(String name, LlmConfigProperties.ProviderConfig pc) {
        VllmClient client = new VllmClient(pc.getBaseUrl(), pc.getModel(), pc.getApiKey(),
                name, pc.getConnectTimeoutSeconds(), pc.getTimeoutSeconds(), pc);
        if (alertCallback != null) client.setAlertCallback(alertCallback);
        if (pc.getRateLimitPerMinute() != null) {
            client.setRateLimiter(new LlmRateLimiter(pc.getRateLimitPerMinute()));
        }
        return client;
    }

    private OpenAiClient createOpenAiClient(String name, LlmConfigProperties.ProviderConfig pc) {
        String apiKey = pc.getApiKey();
        OpenAiClient client = new OpenAiClient(pc.getBaseUrl(), pc.getModel(), apiKey,
                name, pc.getConnectTimeoutSeconds(), pc.getTimeoutSeconds(), pc);
        if (alertCallback != null) client.setAlertCallback(alertCallback);
        if (pc.getRateLimitPerMinute() != null) {
            client.setRateLimiter(new LlmRateLimiter(pc.getRateLimitPerMinute()));
        }
        return client;
    }

    private GeminiCliClient createGeminiClient(String name, LlmConfigProperties.ProviderConfig pc) {
        LlmRateLimiter limiter = pc.getRateLimitPerMinute() != null
                ? new LlmRateLimiter(pc.getRateLimitPerMinute()) : null;
        return new GeminiCliClient(name, pc.getCliPath(), pc.getModel(),
                pc.getTimeoutSeconds(), limiter, pc, loginShellProcess);
    }
}
