package com.logsense.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Two bounded pools:
 * <ul>
 *   <li>{@code jobExecutor} - one task per analysis job; bounded queue, rejects when saturated.</li>
 *   <li>{@code chunkExecutor} - parallel chunk workers (mask, detect, aggregate, batch insert). Bounded queue with
 *       CallerRunsPolicy: when workers fall behind, the file-reading thread processes the chunk itself which
 *       naturally throttles reading (backpressure) instead of buffering the whole file in memory.</li>
 * </ul>
 * Both pools drain gracefully on shutdown.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "jobExecutor")
    public ThreadPoolTaskExecutor jobExecutor(LogSenseProperties properties) {
        LogSenseProperties.Jobs jobs = properties.jobs();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(jobs.corePoolSize());
        executor.setMaxPoolSize(jobs.maxPoolSize());
        executor.setQueueCapacity(jobs.queueCapacity());
        executor.setThreadNamePrefix("logsense-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(jobs.awaitTerminationSeconds());
        executor.initialize();
        return executor;
    }

    @Bean(name = "chunkExecutor")
    public ThreadPoolTaskExecutor chunkExecutor(LogSenseProperties properties) {
        LogSenseProperties.Processing p = properties.processing();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(p.chunkThreads());
        executor.setMaxPoolSize(p.chunkThreads());
        executor.setQueueCapacity(p.chunkQueueCapacity());
        executor.setThreadNamePrefix("logsense-chunk-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.jobs().awaitTerminationSeconds());
        executor.initialize();
        return executor;
    }
}
