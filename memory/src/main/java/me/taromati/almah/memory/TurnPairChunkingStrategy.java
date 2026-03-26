package me.taromati.almah.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.memoryengine.model.Chunk;
import me.taromati.memoryengine.spi.ChunkingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * memory-engine ChunkingStrategy SPI 구현.
 * 기존 ChunkingService의 Turn-Pair 청킹을 어댑터로 감싼다.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class TurnPairChunkingStrategy implements ChunkingStrategy {

    private final ChunkingService chunkingService;
    private final ObjectMapper objectMapper;

    @Override
    public List<Chunk> chunk(String content, Map<String, String> metadata) {
        // metadata Map → JSON 직렬화 (ChunkingService가 JSON string을 받음)
        String metadataJson = serializeMetadata(metadata);
        List<ChunkData> chunkDataList = chunkingService.chunk(content, metadataJson);

        // ChunkData → Chunk 변환 (인덱스 부여)
        return IntStream.range(0, chunkDataList.size())
                .mapToObj(i -> new Chunk(chunkDataList.get(i).content(), i, metadata))
                .toList();
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("[TurnPairChunkingStrategy] metadata 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }
}
