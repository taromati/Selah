package me.taromati.almah.llm.config;

import me.taromati.almah.llm.client.LlmClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LlmClient 동적 빈 등록 완료를 알리는 마커 클래스.
 * config 키 이름 → LlmClient 매핑을 보유하여 LlmClientResolver에 전달.
 */
public class LlmClientRegistrar {

    private final Map<String, LlmClient> clients = new LinkedHashMap<>();

    public void register(String configKey, LlmClient client) {
        clients.put(configKey, client);
    }

    public Map<String, LlmClient> getClients() {
        return clients;
    }
}
