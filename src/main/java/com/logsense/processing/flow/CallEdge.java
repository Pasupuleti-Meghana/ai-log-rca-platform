package com.logsense.processing.flow;

/** Directed service-to-service call statistics. */
public record CallEdge(String caller, String callee, long callCount, long errorCount, Double avgLatencyMs,
                       Long maxLatencyMs) {
}
