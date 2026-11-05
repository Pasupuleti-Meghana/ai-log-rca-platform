package com.logsense.domain.model;

import java.time.Instant;

/**
 * Compact projection of a log event kept in memory per trace for transaction-flow reconstruction.
 */
public record FlowEvent(
        long lineNumber,
        Instant timestamp,
        String service,
        LogLevel level,
        String spanId,
        String parentSpanId,
        String message,
        Integer httpStatus,
        Long durationMs) {

    public static final int MAX_MESSAGE_CHARS = 300;

    public static FlowEvent from(ParsedLogEvent e) {
        String msg = e.message();
        if (msg != null && msg.length() > MAX_MESSAGE_CHARS) {
            msg = msg.substring(0, MAX_MESSAGE_CHARS);
        }
        return new FlowEvent(e.lineNumber(), e.timestamp(), e.service(), e.level(), e.spanId(), e.parentSpanId(),
                msg, e.httpStatus(), e.durationMs());
    }

    public boolean isError() {
        return level.isError() || (httpStatus != null && httpStatus >= 500);
    }
}
