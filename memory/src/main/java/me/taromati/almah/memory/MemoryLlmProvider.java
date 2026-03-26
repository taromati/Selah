package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.llm.client.LlmClientResolver;
import me.taromati.almah.llm.client.SamplingParams;
import me.taromati.almah.llm.client.dto.ChatMessage;
import me.taromati.almah.memory.config.MemoryConfigProperties;
import me.taromati.memoryengine.spi.LlmProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * memory-engine LlmProvider SPI 구현.
 * 알마의 LlmClientResolver를 감싸서 LLM 호출을 제공한다.
 */
@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryLlmProvider implements LlmProvider {

    private final LlmClientResolver clientResolver;
    private final MemoryConfigProperties config;

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        var client = clientResolver.resolve(config.getLlmProvider());

        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null) {
            messages.add(ChatMessage.builder().role("system").content(systemPrompt).build());
        }
        messages.add(ChatMessage.builder().role("user").content(userPrompt).build());

        var response = client.chatCompletion(messages, SamplingParams.withTemperature(0.3));
        return response.getContent();
    }
}
