package com.logsense.processing.flow;

import java.time.Instant;
import java.util.List;

/** Reconstructed end-to-end view of one distributed transaction (trace). */
public record TransactionFlow(
        String traceId,
        Instant start,
        Instant end,
        long durationMs,
        List<String> services,
        List<FlowStep> timeline,
        List<CallEdge> edges,
        List<MissingResponse> missingResponses,
        boolean failed) {
}
