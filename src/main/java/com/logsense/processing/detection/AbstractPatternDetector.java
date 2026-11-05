package com.logsense.processing.detection;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.domain.model.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Template Method for detectors: candidate filtering, matching and finding construction are fixed; subclasses
 * supply the category, the pattern and optionally a severity policy.
 */
public abstract class AbstractPatternDetector implements FailureDetector {

    private static final int MAX_EVIDENCE_LINES = 6;

    protected abstract FailureCategory category();

    protected abstract Pattern pattern();

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public final Optional<Finding> detect(ParsedLogEvent event) {
        if (event == null || !isCandidate(event)) {
            return Optional.empty();
        }
        String text = event.searchableText();
        if (!matches(event, text)) {
            return Optional.empty();
        }
        String target = ServiceReferenceExtractor.firstOtherService(event.message(), event.service())
                .or(() -> ServiceReferenceExtractor.firstOtherService(event.stackTrace(), event.service()))
                .orElse(null);
        return Optional.of(Finding.builder()
                .category(category())
                .severity(severity(event))
                .service(event.service())
                .targetService(target)
                .timestamp(event.timestamp())
                .traceId(event.traceId())
                .lineNumber(event.lineNumber())
                .message(event.message())
                .exceptionClass(event.exceptionClass())
                .evidence(evidence(event))
                .detector(name())
                .build());
    }

    protected boolean isCandidate(ParsedLogEvent event) {
        return event.level().isProblem() || (event.httpStatus() != null && event.httpStatus() >= 500);
    }

    protected boolean matches(ParsedLogEvent event, String text) {
        return pattern().matcher(text).find();
    }

    protected Severity severity(ParsedLogEvent event) {
        return switch (effectiveLevel(event)) {
            case FATAL -> Severity.CRITICAL;
            case ERROR -> Severity.HIGH;
            default -> Severity.MEDIUM;
        };
    }

    protected static LogLevel effectiveLevel(ParsedLogEvent event) {
        if (event.level() == LogLevel.UNKNOWN && event.httpStatus() != null && event.httpStatus() >= 500) {
            return LogLevel.ERROR;
        }
        return event.level();
    }

    protected static List<String> evidence(ParsedLogEvent event) {
        List<String> lines = new ArrayList<>();
        if (event.message() != null) {
            lines.add(event.message());
        }
        if (event.stackTrace() != null) {
            List<String> stack = event.stackTrace().lines().map(String::strip).toList();
            stack.stream().limit(3).forEach(lines::add);
            stack.stream().skip(3).filter(l -> l.startsWith("Caused by:")).forEach(lines::add);
        }
        return lines.stream().distinct().limit(MAX_EVIDENCE_LINES).toList();
    }
}
