package com.logsense.processing.detection;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.domain.model.Severity;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Catch-all link at the end of the chain: any exception / stack trace or ERROR/FATAL event. */
@Component
public class ExceptionDetector extends AbstractPatternDetector {

    private static final Pattern ANY = Pattern.compile("(?s).*");

    @Override
    public int order() {
        return 1000;
    }

    @Override
    protected FailureCategory category() {
        return FailureCategory.EXCEPTION;
    }

    @Override
    protected Pattern pattern() {
        return ANY;
    }

    @Override
    protected boolean isCandidate(ParsedLogEvent event) {
        return super.isCandidate(event) || event.exceptionClass() != null;
    }

    @Override
    protected boolean matches(ParsedLogEvent event, String text) {
        return event.exceptionClass() != null || event.stackTrace() != null || effectiveLevel(event).isError();
    }

    @Override
    protected Severity severity(ParsedLogEvent event) {
        if (event.level() == LogLevel.FATAL || (event.exceptionClass() != null && event.exceptionClass().endsWith("Error"))) {
            return Severity.CRITICAL;
        }
        return effectiveLevel(event).isError() ? Severity.MEDIUM : Severity.LOW;
    }
}
