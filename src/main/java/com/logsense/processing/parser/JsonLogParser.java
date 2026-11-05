package com.logsense.processing.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Structured JSON logs (Logback logstash-encoder, ECS / Elastic Common Schema, Log4j2 JsonTemplateLayout).
 * Both flat dotted keys ({@code "log.level"}) and nested objects ({@code {"log":{"level":..}}}) are supported.
 */
@Component
public class JsonLogParser implements LogParser {

    private static final String[] TIMESTAMP = {"@timestamp", "timestamp", "time", "ts", "date"};
    private static final String[] LEVEL = {"log.level", "level", "severity", "levelname", "lvl"};
    private static final String[] SERVICE = {"service.name", "service", "app", "application", "appName", "serviceName"};
    private static final String[] THREAD = {"process.thread.name", "thread_name", "thread"};
    private static final String[] LOGGER = {"log.logger", "logger_name", "logger"};
    private static final String[] TRACE = {"trace.id", "traceId", "trace_id", "mdc.traceId", "mdc.trace_id"};
    private static final String[] SPAN = {"span.id", "spanId", "span_id", "mdc.spanId", "mdc.span_id"};
    private static final String[] PARENT = {"parent.id", "parentSpanId", "parent_span_id", "parentId", "mdc.parentSpanId"};
    private static final String[] CORRELATION = {"correlationId", "correlation_id", "mdc.correlationId",
            "http.request.id", "requestId"};
    private static final String[] MESSAGE = {"message", "msg", "log"};
    private static final String[] STACK = {"error.stack_trace", "stack_trace", "stackTrace", "exception", "error.stack"};
    private static final String[] EXCEPTION_CLASS = {"error.type", "exception_class", "exception.class"};
    private static final String[] STATUS = {"http.response.status_code", "status", "httpStatus", "status_code"};
    private static final String[] DURATION = {"duration_ms", "durationMs", "elapsed_ms", "duration"};

    private final ObjectMapper mapper;

    public JsonLogParser() {
        this(new ObjectMapper());
    }

    public JsonLogParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LogFormat format() {
        return LogFormat.JSON;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.startsWith("{") && t.endsWith("}");
    }

    @Override
    public Optional<ParsedLogEvent> parse(String line, long lineNumber, ParseContext context) {
        if (!supports(line)) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        Instant ts = node(root, TIMESTAMP)
                .flatMap(n -> n.isNumber()
                        ? Optional.of(Instant.ofEpochMilli(n.asLong()))
                        : TimestampParser.parse(n.asText(), context.defaultZone()))
                .orElse(null);
        if (ts == null) {
            return Optional.empty();
        }
        String message = text(root, MESSAGE);
        String stack = text(root, STACK);
        String exClass = text(root, EXCEPTION_CLASS);
        if (exClass == null && stack != null) {
            exClass = ParsedLogEvent.extractExceptionClass(stack.lines().findFirst().orElse(""));
        }
        if (exClass == null) {
            exClass = ParsedLogEvent.findInlineExceptionClass(message);
        }
        Map<String, String> kv = KeyValueExtractor.extract(message);
        String service = firstNonNull(text(root, SERVICE), kv.get(KeyValueExtractor.SERVICE), context.defaultService());
        Integer status = node(root, STATUS).filter(JsonNode::canConvertToInt).map(JsonNode::asInt)
                .or(() -> node(root, STATUS).map(JsonNode::asText).filter(s -> s.matches("\\d{3}")).map(Integer::valueOf))
                .orElse(null);
        Long duration = node(root, DURATION).map(n -> n.isNumber() ? n.asLong()
                : KeyValueExtractor.parseDurationMs(n.asText())).orElse(null);

        return Optional.of(ParsedLogEvent.builder()
                .lineNumber(lineNumber)
                .timestamp(ts)
                .level(LogLevel.from(text(root, LEVEL)))
                .service(service)
                .thread(text(root, THREAD))
                .logger(text(root, LOGGER))
                .traceId(firstNonNull(text(root, TRACE), kv.get(KeyValueExtractor.TRACE_ID)))
                .spanId(firstNonNull(text(root, SPAN), kv.get(KeyValueExtractor.SPAN_ID)))
                .parentSpanId(firstNonNull(text(root, PARENT), kv.get(KeyValueExtractor.PARENT_SPAN_ID)))
                .correlationId(firstNonNull(text(root, CORRELATION), kv.get(KeyValueExtractor.CORRELATION_ID)))
                .message(message == null ? "" : message)
                .exceptionClass(exClass)
                .stackTrace(stack == null ? null
                        : stack.substring(0, Math.min(stack.length(), ParsedLogEvent.MAX_STACK_TRACE_CHARS)))
                .httpStatus(status)
                .durationMs(duration)
                .format(format().name())
                .build());
    }

    private static Optional<JsonNode> node(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode flat = root.get(path);
            if (flat != null && !flat.isNull() && !flat.isContainerNode()) {
                return Optional.of(flat);
            }
            if (path.contains(".")) {
                JsonNode current = root;
                for (String part : path.split("\\.")) {
                    current = current == null ? null : current.get(part);
                }
                if (current != null && !current.isNull() && !current.isContainerNode()) {
                    return Optional.of(current);
                }
            }
        }
        return Optional.empty();
    }

    private static String text(JsonNode root, String... paths) {
        return node(root, paths).map(JsonNode::asText).filter(s -> !s.isBlank()).orElse(null);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
