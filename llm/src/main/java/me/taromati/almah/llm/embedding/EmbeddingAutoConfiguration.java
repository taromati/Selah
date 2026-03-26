package me.taromati.almah.llm.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.taromati.almah.llm.embedding.onnx.OnnxEmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider 판정 결과에 따라 적절한 EmbeddingService 구현체를 빈으로 등록한다.
 * <p>
 * @ConditionalOnProperty 미사용 — @PostConstruct에서 확정된 Java 필드를 읽어 if/else로 분기.
 * @ConditionalOnMissingBean은 테스트 mock 빈과의 충돌 방지용으로만 사용.
 */
@Configuration
public class EmbeddingAutoConfiguration {

    @Bean("llmEmbeddingService")
    @ConditionalOnMissingBean(EmbeddingService.class)
    public EmbeddingService llmEmbeddingService(EmbeddingProperties props, ObjectMapper objectMapper) {
        String provider = props.getProvider(); // @PostConstruct에서 이미 확정됨
        if ("http".equals(provider)) {
            return new HttpEmbeddingService(props, objectMapper);
        } else {
            return new OnnxEmbeddingService(props);
        }
    }
}
