package me.taromati.almah.llm.client;

/**
 * LLM 프로바이더의 능력치.
 * 각 프로바이더가 contextWindow, maxTokens 등을 제공하여
 * Agent 등 상위 모듈이 provider-agnostic하게 동작할 수 있습니다.
 */
public record ProviderCapabilities(
        Integer contextWindow,
        Integer maxTokens,
        Integer charsPerToken,
        Integer recentKeep
) {
    public static ProviderCapabilities empty() {
        return new ProviderCapabilities(null, null, null, null);
    }
}
