package com.logsense.dto;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentDetailDto(
        UUID id,
        UUID jobId,
        String title,
        String rootCauseService,
        FailureCategory rootCauseCategory,
        Severity severity,
        double confidence,
        String summary,
        String aiSummary,
        List<String> causalChain,
        List<String> affectedServices,
        List<String> traceIds,
        Instant firstFailureAt,
        Instant lastFailureAt,
        long durationMs,
        int findingCount,
        List<TimelineEntryDto> timeline,
        List<FindingDto> findings,
        List<String> recommendations,
        Instant createdAt) {
}
