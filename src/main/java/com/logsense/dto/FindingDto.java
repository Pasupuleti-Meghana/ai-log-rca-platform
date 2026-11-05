package com.logsense.dto;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FindingDto(
        UUID id,
        FailureCategory category,
        Severity severity,
        String service,
        String targetService,
        Instant timestamp,
        String traceId,
        long lineNumber,
        String exceptionClass,
        String message,
        List<String> evidence,
        String detector) {
}
