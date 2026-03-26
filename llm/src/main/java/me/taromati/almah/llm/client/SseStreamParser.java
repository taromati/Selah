package me.taromati.almah.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * SSE(Server-Sent Events) 스트림 파서 — OpenAI 호환 chat/completions 스트리밍 응답 처리.
 * <p>
 * {@code data: {...}} 이벤트에서 {@code choices[0].delta.content}를 추출하여
 * 토큰 단위 콜백을 호출하고, 스트림 종료 시 전체 응답을 {@link ChatCompletionResponse}로 조립합니다.
 */
@Slf4j
public final class SseStreamParser {

    private static final String DATA_PREFIX = "data: ";
    private static final String DONE_MARKER = "[DONE]";

    private SseStreamParser() {}

    /**
     * SSE 스트림을 읽어 토큰 콜백 호출 + ChatCompletionResponse 조립.
     *
     * @param inputStream SSE 응답 스트림
     * @param tokenCallback 토큰(delta.content) 수신 콜백
     * @param objectMapper JSON 파서
     * @return 조립된 ChatCompletionResponse (model, usage, 전체 content 포함)
     */
    public static ChatCompletionResponse parseStream(InputStream inputStream,
                                                       Consumer<String> tokenCallback,
                                                       ObjectMapper objectMapper) throws IOException {
        StringBuilder contentAccumulator = new StringBuilder();
        String model = null;
        String id = null;
        String finishReason = null;
        ChatCompletionResponse.Usage usage = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                if (!line.startsWith(DATA_PREFIX)) continue;

                String data = line.substring(DATA_PREFIX.length()).strip();

                if (DONE_MARKER.equals(data)) break;

                try {
                    JsonNode node = objectMapper.readTree(data);

                    // 메타데이터 추출 (첫 청크에서)
                    if (id == null && node.has("id")) {
                        id = node.get("id").asText();
                    }
                    if (model == null && node.has("model")) {
                        model = node.get("model").asText();
                    }

                    // delta.content 추출
                    JsonNode choices = node.get("choices");
                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                        JsonNode firstChoice = choices.get(0);

                        JsonNode delta = firstChoice.get("delta");
                        if (delta != null && delta.has("content")) {
                            String content = delta.get("content").asText();
                            if (content != null && !content.isEmpty()) {
                                contentAccumulator.append(content);
                                tokenCallback.accept(content);
                            }
                        }

                        // finish_reason
                        JsonNode fr = firstChoice.get("finish_reason");
                        if (fr != null && !fr.isNull()) {
                            finishReason = fr.asText();
                        }
                    }

                    // usage (마지막 청크에 포함될 수 있음)
                    JsonNode usageNode = node.get("usage");
                    if (usageNode != null && !usageNode.isNull()) {
                        usage = new ChatCompletionResponse.Usage(
                                usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null,
                                usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null,
                                usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : null
                        );
                    }
                } catch (Exception e) {
                    log.warn("[SseStreamParser] Failed to parse SSE data: {}", data, e);
                }
            }
        }

        // ChatCompletionResponse 조립
        var message = new ChatCompletionResponse.ResponseMessage(
                "assistant", contentAccumulator.toString(), null);
        var choice = new ChatCompletionResponse.Choice(0, message, finishReason);
        return new ChatCompletionResponse(id, "chat.completion", null, model, List.of(choice), usage);
    }
}
