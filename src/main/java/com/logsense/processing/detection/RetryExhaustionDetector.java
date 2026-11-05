package com.logsense.processing.detection;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.domain.model.Severity;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RetryExhaustionDetector extends AbstractPatternDetector {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(retr(?:y|ies) (?:attempts? )?(?:exhausted|exceeded)|max(?:imum)?[ _-]?retries|MaxRetriesExceeded"
                    + "|giving up after \\d+ (?:attempts|retries)|all \\d+ (?:retry )?attempts failed)");
    private static final Pattern ATTEMPT = Pattern.compile("(?i)(?:retry )?attempt\\s*#?(\\d+)\\s*(?:/|of)\\s*(\\d+)");

    @Override
    public int order() {
        return 10;
    }

    @Override
    protected FailureCategory category() {
        return FailureCategory.RETRY_EXHAUSTION;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected boolean matches(ParsedLogEvent event, String text) {
        if (PATTERN.matcher(text).find()) {
            return true;
        }
        Matcher m = ATTEMPT.matcher(text);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) >= Integer.parseInt(m.group(2))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Severity severity(ParsedLogEvent event) {
        return effectiveLevel(event).isError() ? Severity.HIGH : Severity.MEDIUM;
    }
}
