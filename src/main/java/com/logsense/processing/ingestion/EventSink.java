package com.logsense.processing.ingestion;

import com.logsense.domain.model.ParsedLogEvent;

import java.util.List;
import java.util.UUID;

/** Persistence port for parsed events (keeps the pipeline independent of JPA and easy to test). */
@FunctionalInterface
public interface EventSink {

    void saveBatch(UUID jobId, List<ParsedLogEvent> events);
}
