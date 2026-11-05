package com.logsense.dto;

public record ServiceEdgeDto(String caller, String callee, long callCount, long errorCount, Double avgLatencyMs,
                             Long maxLatencyMs, double errorRate) {
}
