package com.logsense.processing.detection;

import com.logsense.domain.model.Finding;
import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chain of Responsibility over {@link FailureDetector}s. The first (most specific) detector that recognises an
 * event produces the finding, so a HikariCP "request timed out" is classified as DATABASE rather than TIMEOUT.
 */
@Component
public class DetectionEngine {

    private final List<FailureDetector> chain;

    public DetectionEngine(List<FailureDetector> detectors) {
        this.chain = detectors.stream().sorted(Comparator.comparingInt(FailureDetector::order)).toList();
    }

    public static DetectionEngine withDefaultDetectors() {
        return new DetectionEngine(List.of(new RetryExhaustionDetector(), new DatabaseFailureDetector(),
                new TimeoutDetector(), new CommunicationFailureDetector(), new ExceptionDetector()));
    }

    public Optional<Finding> detect(ParsedLogEvent event) {
        for (FailureDetector detector : chain) {
            Optional<Finding> finding = detector.detect(event);
            if (finding.isPresent()) {
                return finding;
            }
        }
        return Optional.empty();
    }

    public List<Finding> detectAll(Collection<ParsedLogEvent> events) {
        return events.stream().map(this::detect).flatMap(Optional::stream).toList();
    }

    public List<String> chainOrder() {
        return chain.stream().map(FailureDetector::name).toList();
    }
}
