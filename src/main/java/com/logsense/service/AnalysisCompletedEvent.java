package com.logsense.service;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.JobStatus;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Observer-pattern notification emitted when a job reaches a terminal state. */
public record AnalysisCompletedEvent(UUID jobId, JobStatus status, List<FailureCategory> rootCauseCategories,
                                     Duration processingTime) {
}
