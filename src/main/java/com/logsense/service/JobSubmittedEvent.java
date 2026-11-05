package com.logsense.service;

import java.util.UUID;

/** Published when a job has been persisted; dispatched for async processing after the transaction commits. */
public record JobSubmittedEvent(UUID jobId) {
}
