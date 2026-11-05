package com.logsense.dto;

import com.logsense.domain.model.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String fileName,
        long fileSize,
        JobStatus status,
        int progress,
        String detectedFormat,
        long totalLines,
        long parsedEvents,
        long unparsedLines,
        int findingsCount,
        int incidentsCount,
        int traceCount,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Long processingTimeMs) {
}
