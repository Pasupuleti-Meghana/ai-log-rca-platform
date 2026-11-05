package com.logsense.support;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.FlowEvent;
import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.domain.model.Severity;

import java.time.Instant;
import java.util.List;

/** Test data builders shared by unit tests. */
public final class TestEvents {

    public static final Instant T0 = Instant.parse("2024-10-15T10:00:00Z");

    private TestEvents() {
    }

    public static ParsedLogEvent event(String service, LogLevel level, String message) {
        return ParsedLogEvent.builder().lineNumber(1).timestamp(T0).service(service).level(level).message(message)
                .build();
    }

    public static ParsedLogEvent withStack(String service, String message, String stack) {
        return event(service, LogLevel.ERROR, message).withStackTrace(stack)
                .withExceptionClass(ParsedLogEvent.extractExceptionClass(stack));
    }

    public static Finding finding(String service, FailureCategory category, Severity severity, long offsetMs,
                                  String targetService) {
        return Finding.builder()
                .service(service)
                .category(category)
                .severity(severity)
                .timestamp(T0.plusMillis(offsetMs))
                .targetService(targetService)
                .traceId("trace-1")
                .message(category + " in " + service)
                .evidence(List.of(category + " evidence from " + service))
                .detector("test")
                .build();
    }

    public static FlowEvent flow(long offsetMs, String service, LogLevel level, String message) {
        return new FlowEvent(offsetMs, T0.plusMillis(offsetMs), service, level, null, null, message, null, null);
    }

    public static FlowEvent span(long offsetMs, String service, String spanId, String parentSpanId, LogLevel level) {
        return new FlowEvent(offsetMs, T0.plusMillis(offsetMs), service, level, spanId, parentSpanId, "span event",
                null, null);
    }
}