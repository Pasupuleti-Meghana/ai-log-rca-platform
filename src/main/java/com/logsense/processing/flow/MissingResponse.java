package com.logsense.processing.flow;

import java.time.Instant;

/**
 * A request that had no matching response within the configured window.
 *
 * @param respondedAt {@code null} when no response was ever observed
 */
public record MissingResponse(String traceId, String caller, String callee, Instant requestedAt, Instant respondedAt,
                              Long waitedMs, String reason) {
}
