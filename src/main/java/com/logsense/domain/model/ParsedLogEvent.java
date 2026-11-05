package com.logsense.domain.model;

import lombok.Builder;
import lombok.With;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, normalized representation of one logical log event (a log line plus any continuation lines
 * such as stack traces).
 */
@Builder(toBuilder = true)
@With
public record ParsedLogEvent(
        long lineNumber,
        Instant timestamp,
        String service,
        LogLevel level,
        String thread,
        String logger,
        String traceId,
        String correlationId,
        String spanId,
        String parentSpanId,
        String message,
        String exceptionClass,
        String stackTrace,
        Integer httpStatus,
        Long durationMs,
        String format) {

    public static final int MAX_STACK_TRACE_CHARS = 8_000;

    private static final Pattern EXCEPTION_CLASS = Pattern.compile(
            "^(?:Caused by:\\s*)?((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable|Fault))\\b");
    private static final Pattern INLINE_EXCEPTION = Pattern.compile(
            "\\b((?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error))\\b");

    public ParsedLogEvent {
        level = level == null ? LogLevel.UNKNOWN : level;
    }

    /** Appends a continuation line (stack frame, "Caused by", wrapped message) to this event. */
    public ParsedLogEvent appendContinuation(String line) {
        String current = stackTrace == null ? "" : stackTrace;
        String updated = current.length() >= MAX_STACK_TRACE_CHARS
                ? current
                : (current.isEmpty() ? line : current + "\n" + line);
        if (updated.length() > MAX_STACK_TRACE_CHARS) {
            updated = updated.substring(0, MAX_STACK_TRACE_CHARS);
        }
        String exClass = exceptionClass;
        if (exClass == null) {
            exClass = extractExceptionClass(line.trim());
        }
        return toBuilder().stackTrace(updated).exceptionClass(exClass).build();
    }

    public static String extractExceptionClass(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = EXCEPTION_CLASS.matcher(text.trim());
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    public static String findInlineExceptionClass(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = INLINE_EXCEPTION.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Full searchable text used by failure detectors. */
    public String searchableText() {
        StringBuilder sb = new StringBuilder();
        if (message != null) {
            sb.append(message);
        }
        if (exceptionClass != null) {
            sb.append('\n').append(exceptionClass);
        }
        if (stackTrace != null) {
            sb.append('\n').append(stackTrace);
        }
        return sb.toString();
    }
}
