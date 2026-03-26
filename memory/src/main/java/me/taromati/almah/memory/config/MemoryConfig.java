package me.taromati.almah.memory.config;

import me.taromati.memoryengine.config.MemoryEngineProperties;
import me.taromati.memoryengine.reranker.OnnxRerankerService;
import me.taromati.memoryengine.reranker.RerankerConfig;
import me.taromati.memoryengine.search.HnswVectorIndex;
import me.taromati.memoryengine.spi.VectorIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RerankerConfig.class)
public class MemoryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "memory-engine.hnsw", name = "enabled", havingValue = "true")
    public VectorIndex hnswVectorIndex(MemoryEngineProperties properties) {
        int dimensions = properties.getEmbedding().getDimensions();
        return new HnswVectorIndex(dimensions);
    }

    @Bean
    @ConditionalOnProperty(prefix = "memory-engine.reranker", name = "enabled", havingValue = "true")
    public OnnxRerankerService onnxRerankerService(RerankerConfig config) {
        return new OnnxRerankerService(config);
    }
}
