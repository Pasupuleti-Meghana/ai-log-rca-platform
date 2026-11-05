package com.logsense.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands submitted jobs to the bounded job executor once the creating transaction has committed (so the worker
 * always sees the persisted job).
 */
@Slf4j
@Component
public class JobDispatcher {

    private final TaskExecutor jobExecutor;
    private final AnalysisJobProcessor processor;

    public JobDispatcher(@Qualifier("jobExecutor") TaskExecutor jobExecutor, AnalysisJobProcessor processor) {
        this.jobExecutor = jobExecutor;
        this.processor = processor;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onJobSubmitted(JobSubmittedEvent event) {
        try {
            jobExecutor.execute(() -> processor.process(event.jobId()));
        } catch (TaskRejectedException e) {
            log.warn("Job {} rejected: executor saturated", event.jobId());
            processor.reject(event.jobId(), "Server busy: too many queued analysis jobs, please retry later");
        }
    }
}
