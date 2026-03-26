package me.taromati.almah.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.config.MemoryConfigProperties;
import me.taromati.memoryengine.spi.ConsolidationPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * memory-engine ConsolidationPolicy SPI 구현.
 * maxAge, groupingUnit을 config에서 읽고, postProcess에서 감사 로그를 기록한다.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryConsolidationPolicy implements ConsolidationPolicy {

    private final MemoryConfigProperties config;

    @Override
    public Duration maxAge() {
        return Duration.ofDays(config.getMaxChunkAgeDays());
    }

    @Override
    public int groupingUnit() {
        return 7; // 주 단위
    }

    @Override
    public void postProcess(List<String> oldSourceIds, String newSourceId) {
        log.info("[MemoryConsolidationPolicy] 통합 완료: {}건 → {}", oldSourceIds.size(), newSourceId);
    }
}
