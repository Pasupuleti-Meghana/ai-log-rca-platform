package com.logsense.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Observer: records metrics and logs a summary whenever an analysis finishes. */
@Slf4j
@Component
public class AnalysisCompletionListener {

    private final LogSenseMetrics metrics;

    public AnalysisCompletionListener(LogSenseMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onCompleted(AnalysisCompletedEvent event) {
        metrics.jobFinished(event.status(), event.processingTime());
        event.rootCauseCategories().forEach(metrics::incidentDetected);
        log.info("Analysis job {} finished with status {} in {} ms ({} incident(s))", event.jobId(), event.status(),
                event.processingTime().toMillis(), event.rootCauseCategories().size());
    }
}
