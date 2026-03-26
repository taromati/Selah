package me.taromati.almah.llm.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 호환 HTTP API를 통한 임베딩 서비스.
 * EmbeddingAutoConfiguration의 @Bean에서만 등록 — @Service 미사용.
 */
@Slf4j
public class HttpEmbeddingService implements EmbeddingService {

    private final EmbeddingProperties embeddingProps;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpEmbeddingService(EmbeddingProperties embeddingProps, ObjectMapper objectMapper) {
        this.embeddingProps = embeddingProps;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public float[] embed(String text) {
        List<float[]> embeddings = embedBatch(List.of(text));
        return embeddings.isEmpty() ? null : embeddings.getFirst();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        String baseUrl = embeddingProps.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            log.error("[HttpEmbeddingService] llm.embedding.base-url 미설정. 임베딩을 사용하려면 config.yml에 명시적으로 설정하세요.");
            return List.of();
        }
        String url = baseUrl + "/embeddings";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String apiKey = embeddingProps.getApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.setBearerAuth(apiKey);
            }

            Map<String, Object> requestBody = Map.of(
                    "model", embeddingProps.getModel(),
                    "input", texts
            );

            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    httpEntity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseEmbeddingResponse(response.getBody());
            } else {
                log.error("[HttpEmbeddingService] 임베딩 API 응답 오류: {}", response.getStatusCode());
            }
        } catch (ResourceAccessException e) {
            log.error("[HttpEmbeddingService] 임베딩 서버 연결 실패: {}", e.getMessage());
        } catch (Exception e) {
            log.error("[HttpEmbeddingService] Embedding request failed: {}", e.getMessage());
        }

        return List.of();
    }

    private List<float[]> parseEmbeddingResponse(String responseBody) {
        List<float[]> embeddings = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");

            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode item : dataArray) {
                    JsonNode embeddingNode = item.get("embedding");
                    if (embeddingNode != null && embeddingNode.isArray()) {
                        float[] embedding = new float[embeddingNode.size()];
                        for (int i = 0; i < embeddingNode.size(); i++) {
                            embedding[i] = embeddingNode.get(i).floatValue();
                        }
                        embeddings.add(embedding);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HttpEmbeddingService] Failed to parse embedding response: {}", e.getMessage());
        }

        return embeddings;
    }
}
