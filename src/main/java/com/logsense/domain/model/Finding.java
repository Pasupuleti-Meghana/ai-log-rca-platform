package com.logsense.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * A failure signal produced by a {@code FailureDetector}.
 *
 * @param targetService downstream service implicated by this finding (e.g. "timed out calling inventory-service"),
 *                      may be {@code null}
 */
@Builder(toBuilder = true)
public record Finding(
        FailureCategory category,
        Severity severity,
        String service,
        String targetService,
        Instant timestamp,
        String traceId,
        long lineNumber,
        String message,
        String exceptionClass,
        List<String> evidence,
        String detector) {

    public Finding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
