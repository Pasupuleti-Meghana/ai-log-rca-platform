package com.logsense.service;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.JobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Custom business metrics exposed via /actuator/prometheus. */
@Component
public class LogSenseMetrics {

    private final MeterRegistry registry;
    private final Counter logsParsed;
    private final Counter findingsDetected;
    private final Timer processingTimer;
    private final AtomicInteger activeJobs = new AtomicInteger();

    public LogSenseMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.logsParsed = Counter.builder("logsense.logs.parsed")
                .description("Number of log events parsed").register(registry);
        this.findingsDetected = Counter.builder("logsense.findings.detected")
                .description("Number of failure findings detected").register(registry);
        this.processingTimer = Timer.builder("logsense.job.processing.time")
                .description("End-to-end analysis job processing time").register(registry);
        Gauge.builder("logsense.jobs.active", activeJobs, AtomicInteger::get)
                .description("Jobs currently being processed").register(registry);
    }

    public void logsParsed(long count) {
        logsParsed.increment(count);
    }

    public void findingsDetected(long count) {
        if (count > 0) {
            findingsDetected.increment(count);
        }
    }

    public void incidentDetected(FailureCategory category) {
        registry.counter("logsense.incidents.detected", "category", category.name()).increment();
    }

    public void jobFinished(JobStatus status, Duration took) {
        processingTimer.record(took);
        registry.counter("logsense.jobs.finished", "status", status.name()).increment();
    }

    public void jobStarted() {
        activeJobs.incrementAndGet();
    }

    public void jobEnded() {
        activeJobs.decrementAndGet();
    }
}
