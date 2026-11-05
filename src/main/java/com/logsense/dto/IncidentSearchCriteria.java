package com.logsense.dto;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;

import java.time.Instant;
import java.util.UUID;

public record IncidentSearchCriteria(String service, Severity severity, FailureCategory category, UUID jobId,
                                     Instant from, Instant to) {

    public static IncidentSearchCriteria empty() {
        return new IncidentSearchCriteria(null, null, null, null, null, null);
    }
}
