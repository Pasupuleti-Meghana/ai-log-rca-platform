package com.logsense.rca;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;

import java.time.Instant;

public record TimelineItem(Instant occurredAt, long offsetMs, String service, FailureCategory category,
                           Severity severity, String description, boolean rootCause) {
}
