package com.logsense.dto;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;

import java.time.Instant;

public record TimelineEntryDto(Instant occurredAt, long offsetMs, String service, FailureCategory category,
                               Severity severity, String description) {
}
