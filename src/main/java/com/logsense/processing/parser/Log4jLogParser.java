package com.logsense.processing.parser;

import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classic Log4j / Logback patterns:
 * <pre>
 * 2024-11-02 14:20:01,101 [http-nio-8081-exec-3] INFO  c.l.order.CheckoutService - message
 * 2024-11-02 14:20:01,101 ERROR [main] com.acme.Foo:42 - message
 * </pre>
 */
@Component
public class Log4jLogParser extends AbstractRegexLogParser {

    private static final String LEVELS = "TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL";
    private static final Pattern PATTERN = Pattern.compile(
            "^(?<ts>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+"
                    + "(?:\\[(?<thread1>[^\\]]+)\\]\\s+(?<level1>" + LEVELS + ")"
                    + "|(?<level2>" + LEVELS + ")\\s+\\[(?<thread2>[^\\]]+)\\])\\s+"
                    + "(?<logger>[\\w.$]+)(?::\\d+)?\\s+-\\s(?<msg>.*)$");

    @Override
    public LogFormat format() {
        return LogFormat.LOG4J;
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected ParsedLogEvent.ParsedLogEventBuilder map(Matcher m, ParseContext context) {
        String level = m.group("level1") != null ? m.group("level1") : m.group("level2");
        String thread = m.group("thread1") != null ? m.group("thread1") : m.group("thread2");
        return ParsedLogEvent.builder()
                .timestamp(timestamp(m.group("ts"), context))
                .level(level(level))
                .thread(thread.trim())
                .logger(m.group("logger"))
                .message(m.group("msg").trim());
    }
}
