package com.logsense.processing.parser;

import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Boot default console/file pattern, e.g.
 * <pre>2024-10-15T10:00:00.120Z  INFO 1 --- [order-service] [nio-8080-exec-1] [6a1b...-3c4d...] c.l.OrderController : msg</pre>
 * Supports Boot 2.x (no application name / correlation brackets) and Boot 3.2+ (application name and
 * {@code [traceId-spanId]} correlation brackets).
 */
@Component
public class SpringBootLogParser extends AbstractRegexLogParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^(?<ts>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+"
                    + "(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s+(?<pid>\\d+)\\s+---\\s+"
                    + "(?<brackets>(?:\\[[^\\]]*\\]\\s*)+)(?<logger>[^\\s\\[]\\S*)\\s+:\\s?(?<msg>.*)$");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern TRACE_SPAN = Pattern.compile("^([0-9a-fA-F]{8,32})[-,]([0-9a-fA-F]{8,16})$");

    @Override
    public LogFormat format() {
        return LogFormat.SPRING_BOOT;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected ParsedLogEvent.ParsedLogEventBuilder map(Matcher m, ParseContext context) {
        ParsedLogEvent.ParsedLogEventBuilder b = ParsedLogEvent.builder()
                .timestamp(timestamp(m.group("ts"), context))
                .level(level(m.group("level")))
                .logger(m.group("logger"))
                .message(m.group("msg").trim());

        List<String> plain = new ArrayList<>();
        Matcher bm = BRACKET.matcher(m.group("brackets"));
        while (bm.find()) {
            String content = bm.group(1).trim();
            Matcher ts = TRACE_SPAN.matcher(content);
            if (ts.matches()) {
                b.traceId(ts.group(1)).spanId(ts.group(2));
            } else if (!content.isEmpty()) {
                plain.add(content);
            }
        }
        if (plain.size() >= 2) {
            b.service(plain.get(0)).thread(plain.get(1));
        } else if (plain.size() == 1) {
            b.thread(plain.get(0));
        }
        return b;
    }
}
