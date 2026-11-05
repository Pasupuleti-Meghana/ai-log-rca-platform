package com.logsense.processing.parser;

import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback parser: any line that starts with a recognisable timestamp. Level and service are located heuristically.
 */
@Component
public class GenericLogParser extends AbstractRegexLogParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^\\[?(?<ts>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|\\s?[+-]\\d{2}:?\\d{2}|\\s?UTC)?"
                    + "|[A-Z][a-z]{2}\\s+\\d{1,2} \\d{2}:\\d{2}:\\d{2}|\\d{10}(?:\\d{3})?)\\]?[\\s|,;-]+(?<rest>.*)$");
    private static final Pattern LEVEL = Pattern.compile(
            "\\b(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|SEVERE|FATAL|CRITICAL)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE = Pattern.compile(
            "\\[([a-z][a-z0-9]*(?:-[a-z0-9]+)*-(?:service|gateway|svc|api))\\]");

    @Override
    public LogFormat format() {
        return LogFormat.GENERIC;
    }

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected ParsedLogEvent.ParsedLogEventBuilder map(Matcher m, ParseContext context) {
        String rest = m.group("rest").trim();
        Matcher lm = LEVEL.matcher(rest);
        LogLevel level = lm.find() ? LogLevel.from(lm.group(1)) : LogLevel.INFO;
        Matcher sm = SERVICE.matcher(rest);
        return ParsedLogEvent.builder()
                .timestamp(timestamp(m.group("ts"), context))
                .level(level)
                .service(sm.find() ? sm.group(1) : null)
                .message(rest);
    }
}
