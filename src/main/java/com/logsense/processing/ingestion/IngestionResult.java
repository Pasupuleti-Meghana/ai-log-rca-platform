package com.logsense.processing.ingestion;

import com.logsense.domain.model.Finding;
import com.logsense.processing.flow.TraceAggregator;

import java.util.List;

/**
 * @param totalFindings all findings detected (the list may be capped by {@code maxFindings})
 */
public record IngestionResult(long totalLines, long parsedEvents, long unparsedLines, String detectedFormat,
                              List<Finding> findings, long totalFindings, TraceAggregator traces) {
}
