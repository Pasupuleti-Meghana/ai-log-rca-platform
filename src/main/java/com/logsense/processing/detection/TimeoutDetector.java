package com.logsense.processing.detection;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class TimeoutDetector extends AbstractPatternDetector {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(SocketTimeoutException|TimeoutException|ReadTimeoutException|timed out|read timeout|connect timeout"
                    + "|request timeout|deadline exceeded|GATEWAY_TIMEOUT|Gateway Time-?out|\\b504\\b)");

    @Override
    public int order() {
        return 30;
    }

    @Override
    protected FailureCategory category() {
        return FailureCategory.TIMEOUT;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected boolean matches(ParsedLogEvent event, String text) {
        return (event.httpStatus() != null && event.httpStatus() == 504) || PATTERN.matcher(text).find();
    }
}
