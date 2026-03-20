package me.taromati.almah.memory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class MemoryAsyncConfig {

    @Bean(name = "memorySlowPathExecutor")
    public Executor memorySlowPathExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("MemorySlowPath-");
        executor.setRejectedExecutionHandler((r, e) -> {
            org.slf4j.LoggerFactory.getLogger(MemoryAsyncConfig.class)
                    .warn("[MemorySlowPath] Queue overflow, task rejected");
        });
        executor.initialize();
        return executor;
    }
}
