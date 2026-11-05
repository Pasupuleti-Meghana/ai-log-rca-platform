package com.logsense.processing.detection;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Network-level / inter-service failures: refused connections, DNS, 502/503, open circuit breakers. */
@Component
public class CommunicationFailureDetector extends AbstractPatternDetector {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(ConnectException|Connection refused|UnknownHostException|NoRouteToHostException|Connection reset"
                    + "|Broken pipe|CallNotPermittedException|circuit ?breaker\\b.{0,40}\\bopen|SERVICE_UNAVAILABLE"
                    + "|Service Unavailable|BAD_GATEWAY|Bad Gateway|\\b50[23]\\b|Name or service not known"
                    + "|DNS resolution failed|Failed to resolve|no healthy upstream|PrematureCloseException"
                    + "|Connection prematurely closed)");

    @Override
    public int order() {
        return 40;
    }

    @Override
    protected FailureCategory category() {
        return FailureCategory.COMMUNICATION;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected boolean matches(ParsedLogEvent event, String text) {
        Integer s = event.httpStatus();
        return (s != null && (s == 502 || s == 503)) || PATTERN.matcher(text).find();
    }
}
