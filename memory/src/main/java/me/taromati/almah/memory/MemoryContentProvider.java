package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.memory.db.entity.MemoryChunkEntity;
import me.taromati.almah.memory.db.repository.MemoryChunkRepository;
import me.taromati.memoryengine.reranker.ContentProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryContentProvider implements ContentProvider {

    private final MemoryChunkRepository chunkRepository;

    @Override
    public String getContent(String documentId) {
        return chunkRepository.findById(documentId)
                .map(MemoryChunkEntity::getContent)
                .orElse(null);
    }
}
