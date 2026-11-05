package com.logsense.processing.parser;

import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template Method base for regex based parsers: matching, timestamp normalisation and MDC key/value enrichment
 * are shared; subclasses only describe how to map regex groups to an event.
 */
public abstract class AbstractRegexLogParser implements LogParser {

    protected abstract Pattern pattern();

    protected abstract ParsedLogEvent.ParsedLogEventBuilder map(Matcher matcher, ParseContext context);

    @Override
    public boolean supports(String line) {
        return line != null && pattern().matcher(line).matches();
    }

    @Override
    public final Optional<ParsedLogEvent> parse(String line, long lineNumber, ParseContext context) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern().matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        ParsedLogEvent.ParsedLogEventBuilder builder = map(matcher, context);
        if (builder == null) {
            return Optional.empty();
        }
        ParsedLogEvent event = builder.lineNumber(lineNumber).format(format().name()).build();
        if (event.timestamp() == null) {
            return Optional.empty();
        }
        return Optional.of(enrich(event, context));
    }

    protected Instant timestamp(String raw, ParseContext context) {
        return TimestampParser.parse(raw, context.defaultZone()).orElse(null);
    }

    protected static LogLevel level(String raw) {
        return LogLevel.from(raw);
    }

    /** Fills missing correlation fields from key=value pairs in the message and applies defaults. */
    protected ParsedLogEvent enrich(ParsedLogEvent event, ParseContext context) {
        Map<String, String> kv = KeyValueExtractor.extract(event.message());
        ParsedLogEvent.ParsedLogEventBuilder b = event.toBuilder();
        if (isBlank(event.service())) {
            b.service(kv.getOrDefault(KeyValueExtractor.SERVICE, context.defaultService()));
        }
        if (isBlank(event.traceId())) {
            b.traceId(kv.get(KeyValueExtractor.TRACE_ID));
        }
        if (isBlank(event.spanId())) {
            b.spanId(kv.get(KeyValueExtractor.SPAN_ID));
        }
        if (isBlank(event.parentSpanId())) {
            b.parentSpanId(kv.get(KeyValueExtractor.PARENT_SPAN_ID));
        }
        if (isBlank(event.correlationId())) {
            b.correlationId(kv.get(KeyValueExtractor.CORRELATION_ID));
        }
        if (event.durationMs() == null) {
            b.durationMs(KeyValueExtractor.parseDurationMs(kv.get(KeyValueExtractor.DURATION)));
        }
        if (event.exceptionClass() == null) {
            b.exceptionClass(ParsedLogEvent.findInlineExceptionClass(event.message()));
        }
        return b.build();
    }

    protected static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    protected static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
