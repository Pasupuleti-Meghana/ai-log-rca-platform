package com.logsense.processing.parser;

import com.logsense.domain.model.ParsedLogEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a stream of physical lines into logical events. Continuation lines (stack frames, "Caused by:",
 * exception headers, indented wrapped text) are appended to the previous event.
 * <p>Not thread-safe: one instance per input stream.</p>
 */
public class LogEventAssembler {

    private static final Pattern EXCEPTION_HEADER = Pattern.compile(
            "^(?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable)(?::.*)?$");
    private static final Pattern MORE_FRAMES = Pattern.compile("^\\.\\.\\. \\d+ (?:more|common frames omitted).*");

    private final LogParserFactory factory;
    private final ParseContext context;
    private final Map<LogFormat, Long> formatCounts = new EnumMap<>(LogFormat.class);
    private LogParser lastParser;
    private ParsedLogEvent current;
    private long unparsedLines;

    public LogEventAssembler(LogParserFactory factory, ParseContext context) {
        this.factory = factory;
        this.context = context;
    }

    public static boolean isContinuation(String line) {
        if (line == null || line.isEmpty()) {
            return true;
        }
        char c = line.charAt(0);
        if (c == ' ' || c == '\t') {
            return true;
        }
        return line.startsWith("at ") || line.startsWith("Caused by:") || line.startsWith("Suppressed:")
                || MORE_FRAMES.matcher(line).matches() || EXCEPTION_HEADER.matcher(line).matches();
    }

    /**
     * Feeds a physical line.
     *
     * @return the previous event if this line starts a new one
     */
    public Optional<ParsedLogEvent> accept(String line, long lineNumber) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        if (isContinuation(line) && current != null) {
            current = current.appendContinuation(stripTrailing(line));
            return Optional.empty();
        }
        Optional<LogParserFactory.ParsedEventWithParser> parsed = factory.parse(line, lineNumber, context, lastParser);
        if (parsed.isEmpty()) {
            if (current != null) {
                current = current.appendContinuation(stripTrailing(line));
            } else {
                unparsedLines++;
            }
            return Optional.empty();
        }
        lastParser = parsed.get().parser();
        formatCounts.merge(lastParser.format(), 1L, Long::sum);
        ParsedLogEvent previous = current;
        current = parsed.get().event();
        return Optional.ofNullable(previous);
    }

    public Optional<ParsedLogEvent> flush() {
        ParsedLogEvent last = current;
        current = null;
        return Optional.ofNullable(last);
    }

    public long unparsedLines() {
        return unparsedLines;
    }

    public Map<LogFormat, Long> formatCounts() {
        return Map.copyOf(formatCounts);
    }

    /** Dominant format, or "MIXED" when no single format covers at least 80% of events. */
    public String dominantFormat() {
        long total = formatCounts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return LogFormat.GENERIC.name();
        }
        Map.Entry<LogFormat, Long> top = formatCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();
        return top.getValue() * 100 / total >= 80 ? top.getKey().name() : "MIXED";
    }

    private static String stripTrailing(String line) {
        return line.stripTrailing();
    }
}
