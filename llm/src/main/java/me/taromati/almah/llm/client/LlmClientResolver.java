package me.taromati.almah.llm.client;

import me.taromati.almah.llm.config.LlmClientRegistrar;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로바이더 이름으로 LlmClient를 해석합니다.
 * LlmClientRegistrar 빈들로부터 config 키 → LlmClient 매핑을 수집합니다.
 */
@Component
public class LlmClientResolver {

    private final Map<String, LlmClient> clients = new LinkedHashMap<>();

    public LlmClientResolver(List<LlmClientRegistrar> registrars) {
        for (LlmClientRegistrar registrar : registrars) {
            clients.putAll(registrar.getClients());
        }
    }

    /**
     * 프로바이더 이름으로 LlmClient 해석
     *
     * @param providerName config.yml의 프로바이더 키 이름 (예: "vllm", "openai")
     * @return 해당 LlmClient
     * @throws IllegalArgumentException 미등록 프로바이더
     */
    public LlmClient resolve(String providerName) {
        LlmClient client = clients.get(providerName);
        if (client == null) {
            throw new IllegalArgumentException(
                    "프로바이더 '" + providerName + "'가 등록되지 않았습니다. 사용 가능: " + getAvailableProviders());
        }
        return client;
    }

    /**
     * 사용 가능한 프로바이더 목록
     */
    public List<String> getAvailableProviders() {
        return new ArrayList<>(clients.keySet());
    }
}
