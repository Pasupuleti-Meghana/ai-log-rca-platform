package com.logsense.kafka;

import com.logsense.dto.JobResponse;
import com.logsense.service.JobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Optional streaming ingestion (profile {@code kafka}). Each poll batch of raw log lines becomes an analysis job,
 * reusing the exact same pipeline as file uploads. Kafka auto-configuration is excluded unless the profile is active.
 * Producers should key records by traceId so a transaction's lines land in the same partition (ordering + locality).
 */
@Slf4j
@Component
@Profile("kafka")
public class KafkaLogIngestionListener {

    private final JobService jobService;

    public KafkaLogIngestionListener(JobService jobService) {
        this.jobService = jobService;
    }

    @KafkaListener(topics = "${logsense.kafka.topic:logsense.raw-logs}", groupId = "${logsense.kafka.group:logsense}",
            batch = "true")
    public void onBatch(List<String> lines) throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        String name = "kafka-batch-" + Instant.now().toEpochMilli() + ".log";
        Path file = jobService.uploadDir().resolve(UUID.randomUUID() + "_" + name);
        Files.write(file, lines, StandardCharsets.UTF_8);
        JobResponse job = jobService.submitStoredFile(file, name);
        log.info("Kafka batch of {} lines submitted as job {}", lines.size(), job.id());
    }
}
