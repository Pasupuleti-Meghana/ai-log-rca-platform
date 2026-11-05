package com.logsense.dto;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentSummaryDto(
        UUID id,
        UUID jobId,
        String title,
        String rootCauseService,
        FailureCategory rootCauseCategory,
        Severity severity,
        double confidence,
        List<String> affectedServices,
        Instant firstFailureAt,
        long durationMs,
        int findingCount,
        Instant createdAt) {
}
