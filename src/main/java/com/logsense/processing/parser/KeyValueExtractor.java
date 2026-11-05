package com.logsense.processing.parser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts well-known MDC-style {@code key=value} pairs that frequently appear inside free-text log messages
 * (e.g. {@code traceId=abc spanId=def service=order-service}).
 */
public final class KeyValueExtractor {

    public static final String SERVICE = "service";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String PARENT_SPAN_ID = "parentSpanId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String DURATION = "duration";

    private static final Pattern KV = Pattern.compile(
            "\\b([A-Za-z][A-Za-z_.-]*)\\s*[=:]\\s*\"?([A-Za-z0-9_.:/-]+)\"?");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("service", SERVICE), Map.entry("app", SERVICE), Map.entry("application", SERVICE),
            Map.entry("service.name", SERVICE), Map.entry("servicename", SERVICE),
            Map.entry("traceid", TRACE_ID), Map.entry("trace_id", TRACE_ID), Map.entry("trace.id", TRACE_ID),
            Map.entry("x-b3-traceid", TRACE_ID), Map.entry("transactionid", TRACE_ID), Map.entry("txid", TRACE_ID),
            Map.entry("spanid", SPAN_ID), Map.entry("span_id", SPAN_ID), Map.entry("span.id", SPAN_ID),
            Map.entry("parentspanid", PARENT_SPAN_ID), Map.entry("parent_span_id", PARENT_SPAN_ID),
            Map.entry("parent.id", PARENT_SPAN_ID), Map.entry("parentid", PARENT_SPAN_ID),
            Map.entry("correlationid", CORRELATION_ID), Map.entry("correlation_id", CORRELATION_ID),
            Map.entry("x-correlation-id", CORRELATION_ID), Map.entry("requestid", CORRELATION_ID),
            Map.entry("request_id", CORRELATION_ID),
            Map.entry("rt", DURATION), Map.entry("request_time", DURATION), Map.entry("duration", DURATION),
            Map.entry("duration_ms", DURATION), Map.entry("elapsed", DURATION));

    private KeyValueExtractor() {
    }

    public static Map<String, String> extract(String text) {
        Map<String, String> result = new HashMap<>();
        if (text == null || text.isEmpty() || text.indexOf('=') < 0 && text.indexOf(':') < 0) {
            return result;
        }
        Matcher m = KV.matcher(text);
        while (m.find()) {
            String canonical = ALIASES.get(m.group(1).toLowerCase(Locale.ROOT));
            if (canonical != null) {
                result.putIfAbsent(canonical, m.group(2));
            }
        }
        return result;
    }

    /** Parses durations such as {@code 0.123} (seconds, nginx style), {@code 123ms} or {@code 123}. */
    public static Long parseDurationMs(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return null;
        }
        try {
            String v = raw.toLowerCase(Locale.ROOT);
            if (v.endsWith("ms")) {
                return Long.parseLong(v.substring(0, v.length() - 2));
            }
            if (v.endsWith("s")) {
                return Math.round(Double.parseDouble(v.substring(0, v.length() - 1)) * 1000);
            }
            if (v.contains(".")) {
                return Math.round(Double.parseDouble(v) * 1000);
            }
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
