package com.logsense.processing.flow;

import com.logsense.domain.model.LogLevel;

import java.time.Instant;

public record FlowStep(Instant timestamp, long offsetMs, String service, LogLevel level, String spanId,
                       String message) {
}
