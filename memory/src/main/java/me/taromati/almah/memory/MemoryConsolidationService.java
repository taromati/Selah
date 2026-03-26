package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.memoryengine.consolidation.ConsolidationEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * memory-engine ConsolidationEngine에 위임하는 스케줄러.
 * 매일 4시에 오래된 청크를 통합한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryConsolidationService {

    private final ObjectProvider<ConsolidationEngine> consolidationEngineProvider;

    @Scheduled(cron = "0 0 4 * * *")
    public void consolidate() {
        var engine = consolidationEngineProvider.getIfAvailable();
        if (engine == null) {
            log.debug("[MemoryConsolidationService] ConsolidationEngine 비활성 — 통합 스킵");
            return;
        }
        try {
            engine.consolidate();
            log.info("[MemoryConsolidationService] 통합 완료");
        } catch (Exception e) {
            log.warn("[MemoryConsolidationService] 통합 실패: {}", e.getMessage());
        }
    }
}
