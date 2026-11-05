package com.logsense.processing.parser;

import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nginx / Apache common &amp; combined access logs, optionally followed by extra key=value fields
 * (e.g. {@code rt=0.123 upstream=order-service trace_id=...}).
 */
@Component
public class AccessLogParser extends AbstractRegexLogParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^(?<ip>\\S+) \\S+ (?<user>\\S+) \\[(?<ts>\\d{2}/\\w{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4})\\] "
                    + "\"(?<method>[A-Z]+) (?<path>\\S+)(?: [^\"]*)?\" (?<status>\\d{3}) (?<bytes>\\d+|-)"
                    + "(?: \"(?<ref>[^\"]*)\" \"(?<ua>[^\"]*)\")?(?<rest>.*)$");

    @Override
    public LogFormat format() {
        return LogFormat.ACCESS_LOG;
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected ParsedLogEvent.ParsedLogEventBuilder map(Matcher m, ParseContext context) {
        int status = Integer.parseInt(m.group("status"));
        String rest = m.group("rest") == null ? "" : m.group("rest").trim();
        String message = m.group("method") + " " + m.group("path") + " -> " + status
                + (rest.isEmpty() ? "" : " " + rest);
        return ParsedLogEvent.builder()
                .timestamp(timestamp(m.group("ts"), context))
                .level(levelForStatus(status))
                .thread(m.group("ip"))
                .logger("access-log")
                .httpStatus(status)
                .message(message);
    }

    static LogLevel levelForStatus(int status) {
        if (status >= 500) {
            return LogLevel.ERROR;
        }
        return status >= 400 ? LogLevel.WARN : LogLevel.INFO;
    }
}
