package com.logsense.service;

import com.logsense.domain.model.FlowEvent;
import com.logsense.exception.ResourceNotFoundException;
import com.logsense.processing.flow.TransactionFlow;
import com.logsense.processing.flow.TransactionFlowAnalyzer;
import com.logsense.repository.LogEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Rebuilds a single transaction on demand from persisted events (index on trace_id). */
@Service
@Transactional(readOnly = true)
public class TransactionFlowService {

    private final LogEventRepository eventRepository;
    private final TransactionFlowAnalyzer analyzer;
    private final JobService jobService;

    public TransactionFlowService(LogEventRepository eventRepository, TransactionFlowAnalyzer analyzer,
                                  JobService jobService) {
        this.eventRepository = eventRepository;
        this.analyzer = analyzer;
        this.jobService = jobService;
    }

    public TransactionFlow flow(UUID jobId, String traceId) {
        jobService.requireJob(jobId);
        List<FlowEvent> events = eventRepository.findByJobIdAndTraceIdOrderByTimestampAscLineNumberAsc(jobId, traceId)
                .stream()
                .map(e -> new FlowEvent(e.getLineNumber(), e.getTimestamp(), e.getService(), e.getLevel(),
                        e.getSpanId(), e.getParentSpanId(), e.getMessage(), e.getHttpStatus(), e.getDurationMs()))
                .toList();
        if (events.isEmpty()) {
            throw new ResourceNotFoundException("Trace", traceId);
        }
        return analyzer.analyze(traceId, events);
    }
}
