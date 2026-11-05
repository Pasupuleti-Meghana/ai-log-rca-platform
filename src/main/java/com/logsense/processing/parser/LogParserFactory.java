package com.logsense.processing.parser;

import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Factory that owns all {@link LogParser} strategies, auto-detects the dominant format of a file and resolves
 * the right parser per line (files may mix formats).
 */
@Component
public class LogParserFactory {

    private final List<LogParser> parsers;

    public LogParserFactory(List<LogParser> parsers) {
        this.parsers = parsers.stream().sorted(Comparator.comparingInt(LogParser::priority)).toList();
    }

    public static LogParserFactory withDefaultParsers() {
        return new LogParserFactory(List.of(new JsonLogParser(), new SpringBootLogParser(), new Log4jLogParser(),
                new AccessLogParser(), new GenericLogParser()));
    }

    public List<LogParser> parsers() {
        return parsers;
    }

    public Optional<LogParser> parserFor(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        return parsers.stream().filter(p -> p.supports(line)).findFirst();
    }

    /** Majority vote over sample lines; continuation lines (stack frames) are ignored. */
    public LogFormat detectFormat(List<String> sampleLines) {
        Map<LogFormat, Integer> votes = new EnumMap<>(LogFormat.class);
        sampleLines.stream()
                .filter(l -> !LogEventAssembler.isContinuation(l))
                .map(this::parserFor)
                .flatMap(Optional::stream)
                .forEach(p -> votes.merge(p.format(), 1, Integer::sum));
        return votes.entrySet().stream()
                .max(Map.Entry.<LogFormat, Integer>comparingByValue()
                        .thenComparing(e -> -e.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .orElse(LogFormat.GENERIC);
    }

    /**
     * Parses a line trying the {@code preferred} parser first (cheap fast-path for homogeneous files), then all
     * parsers by priority.
     */
    public Optional<ParsedEventWithParser> parse(String line, long lineNumber, ParseContext context, LogParser preferred) {
        if (preferred != null) {
            Optional<ParsedLogEvent> e = preferred.parse(line, lineNumber, context);
            if (e.isPresent()) {
                return Optional.of(new ParsedEventWithParser(e.get(), preferred));
            }
        }
        for (LogParser parser : parsers) {
            if (parser == preferred || !parser.supports(line)) {
                continue;
            }
            Optional<ParsedLogEvent> e = parser.parse(line, lineNumber, context);
            if (e.isPresent()) {
                return Optional.of(new ParsedEventWithParser(e.get(), parser));
            }
        }
        return Optional.empty();
    }

    public record ParsedEventWithParser(ParsedLogEvent event, LogParser parser) {
    }
}
