package com.logsense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Type-safe application configuration ({@code logsense.*}). Every nested group falls back to sensible defaults so
 * that the application (and unit tests) work with partial configuration.
 */
@ConfigurationProperties(prefix = "logsense")
public record LogSenseProperties(Storage storage, Processing processing, Jobs jobs, Ai ai) {

    public LogSenseProperties {
        storage = storage == null ? new Storage(null, null, true) : storage;
        processing = processing == null ? new Processing(0, 0, 0, 0, 0, 0, null, null) : processing;
        jobs = jobs == null ? new Jobs(0, 0, 0, 0) : jobs;
        ai = ai == null ? new Ai(false, null, null, null, null, null, 0, 0.2) : ai;
    }

    public static LogSenseProperties defaults() {
        return new LogSenseProperties(null, null, null, null);
    }

    public record Storage(String uploadDir, List<String> allowedExtensions, boolean deleteAfterProcessing) {
        public Storage {
            uploadDir = uploadDir == null || uploadDir.isBlank()
                    ? System.getProperty("java.io.tmpdir") + "/logsense-uploads" : uploadDir;
            allowedExtensions = allowedExtensions == null || allowedExtensions.isEmpty()
                    ? List.of("log", "txt", "json", "jsonl", "out") : allowedExtensions;
        }
    }

    /**
     * @param batchSize              events per persistence/detection chunk (also the JDBC batch unit)
     * @param chunkThreads           parallel chunk workers (0 = number of CPUs)
     * @param chunkQueueCapacity     bounded queue size; when full the reader thread runs the chunk (backpressure)
     * @param maxTraces              max traces kept in memory per job for flow reconstruction
     * @param maxEventsPerTrace      max compact events kept per trace
     * @param maxFindings            max findings retained per job (all are counted)
     * @param incidentGap            findings separated by more than this gap form separate incidents
     * @param missingResponseTimeout a request without response within this window is a "missing response"
     */
    public record Processing(int batchSize, int chunkThreads, int chunkQueueCapacity, int maxTraces,
                             int maxEventsPerTrace, int maxFindings, Duration incidentGap,
                             Duration missingResponseTimeout) {
        public Processing {
            batchSize = batchSize <= 0 ? 1000 : batchSize;
            chunkThreads = chunkThreads <= 0 ? Runtime.getRuntime().availableProcessors() : chunkThreads;
            chunkQueueCapacity = chunkQueueCapacity <= 0 ? chunkThreads * 2 : chunkQueueCapacity;
            maxTraces = maxTraces <= 0 ? 200_000 : maxTraces;
            maxEventsPerTrace = maxEventsPerTrace <= 0 ? 500 : maxEventsPerTrace;
            maxFindings = maxFindings <= 0 ? 10_000 : maxFindings;
            incidentGap = incidentGap == null ? Duration.ofMinutes(5) : incidentGap;
            missingResponseTimeout = missingResponseTimeout == null ? Duration.ofSeconds(30) : missingResponseTimeout;
        }
    }

    public record Jobs(int corePoolSize, int maxPoolSize, int queueCapacity, int awaitTerminationSeconds) {
        public Jobs {
            corePoolSize = corePoolSize <= 0 ? 2 : corePoolSize;
            maxPoolSize = Math.max(maxPoolSize <= 0 ? 4 : maxPoolSize, corePoolSize);
            queueCapacity = queueCapacity <= 0 ? 100 : queueCapacity;
            awaitTerminationSeconds = awaitTerminationSeconds <= 0 ? 30 : awaitTerminationSeconds;
        }
    }

    public record Ai(boolean enabled, String baseUrl, String apiKey, String model, String chatPath, Duration timeout,
                     int maxFindingsInPrompt, double temperature) {
        public Ai {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl;
            model = model == null || model.isBlank() ? "llama3.1" : model;
            chatPath = chatPath == null || chatPath.isBlank() ? "/v1/chat/completions" : chatPath;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            maxFindingsInPrompt = maxFindingsInPrompt <= 0 ? 15 : maxFindingsInPrompt;
        }
    }
}
