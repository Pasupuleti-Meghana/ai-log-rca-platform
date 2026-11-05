package com.logsense.service;

import com.logsense.ai.AiSummarizer;
import com.logsense.config.LogSenseProperties;
import com.logsense.domain.entity.AnalysisJob;
import com.logsense.domain.entity.IncidentEntity;
import com.logsense.domain.entity.ServiceCallEdgeEntity;
import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.Severity;
import com.logsense.mapper.IncidentMapper;
import com.logsense.processing.flow.CallEdge;
import com.logsense.processing.flow.MissingResponse;
import com.logsense.processing.flow.ServiceCallGraph;
import com.logsense.processing.flow.TraceAggregator;
import com.logsense.processing.flow.TransactionFlow;
import com.logsense.processing.flow.TransactionFlowAnalyzer;
import com.logsense.processing.ingestion.IngestionResult;
import com.logsense.processing.ingestion.LogIngestionPipeline;
import com.logsense.processing.parser.ParseContext;
import com.logsense.rca.RcaEngine;
import com.logsense.rca.RcaResult;
import com.logsense.repository.AnalysisJobRepository;
import com.logsense.repository.IncidentRepository;
import com.logsense.repository.ServiceCallEdgeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates one analysis job: ingest -> reconstruct flows -> RCA -> summarise -> persist incidents.
 */
@Slf4j
@Service
public class AnalysisJobProcessor {

    static final int MAX_FINDINGS_PER_INCIDENT = 500;

    private final AnalysisJobRepository jobRepository;
    private final IncidentRepository incidentRepository;
    private final ServiceCallEdgeRepository edgeRepository;
    private final LogIngestionPipeline pipeline;
    private final TransactionFlowAnalyzer flowAnalyzer;
    private final RcaEngine rcaEngine;
    private final AiSummarizer summarizer;
    private final IncidentMapper incidentMapper;
    private final ApplicationEventPublisher events;
    private final LogSenseMetrics metrics;
    private final LogSenseProperties properties;

    public AnalysisJobProcessor(AnalysisJobRepository jobRepository, IncidentRepository incidentRepository,
                                ServiceCallEdgeRepository edgeRepository, LogIngestionPipeline pipeline,
                                TransactionFlowAnalyzer flowAnalyzer, RcaEngine rcaEngine, AiSummarizer summarizer,
                                IncidentMapper incidentMapper, ApplicationEventPublisher events,
                                LogSenseMetrics metrics, LogSenseProperties properties) {
        this.jobRepository = jobRepository;
        this.incidentRepository = incidentRepository;
        this.edgeRepository = edgeRepository;
        this.pipeline = pipeline;
        this.flowAnalyzer = flowAnalyzer;
        this.rcaEngine = rcaEngine;
        this.summarizer = summarizer;
        this.incidentMapper = incidentMapper;
        this.events = events;
        this.metrics = metrics;
        this.properties = properties;
    }

    public void process(UUID jobId) {
        AnalysisJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus().isTerminal()) {
            log.warn("Job {} not found or already finished; skipping", jobId);
            return;
        }
        Instant started = Instant.now();
        metrics.jobStarted();
        job.markProcessing();
        job = jobRepository.save(job);
        List<FailureCategory> rootCategories = new ArrayList<>();
        Path file = Path.of(job.getStoragePath());
        try (InputStream in = Files.newInputStream(file)) {
            IngestionResult result = pipeline.ingest(jobId, in, job.getFileSize(),
                    ParseContext.forFile(job.getFileName()), p -> jobRepository.updateProgress(jobId, p));

            List<Finding> findings = new ArrayList<>(result.findings());
            ServiceCallGraph graph = reconstructFlows(result.traces(), findings);
            edgeRepository.saveAll(graph.edges().stream().map(e -> toEntity(jobId, e)).toList());

            List<RcaResult> rcaResults = rcaEngine.analyze(findings, graph);
            List<IncidentEntity> incidents = rcaResults.stream()
                    .map(r -> incidentMapper.toEntity(jobId, r, aiSummary(r), MAX_FINDINGS_PER_INCIDENT))
                    .toList();
            incidentRepository.saveAll(incidents);
            rcaResults.forEach(r -> rootCategories.add(r.rootCauseCategory()));

            job.setTotalLines(result.totalLines());
            job.setParsedEvents(result.parsedEvents());
            job.setUnparsedLines(result.unparsedLines());
            job.setDetectedFormat(result.detectedFormat());
            job.setFindingsCount((int) Math.min(Integer.MAX_VALUE,
                    result.totalFindings() + (findings.size() - result.findings().size())));
            job.setTraceCount(result.traces().traceCount());
            job.setIncidentsCount(incidents.size());
            job.markCompleted();
            jobRepository.save(job);
        } catch (Exception e) {
            log.error("Analysis job {} failed", jobId, e);
            job.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
            jobRepository.save(job);
        } finally {
            metrics.jobEnded();
            deleteIfConfigured(file);
        }
        events.publishEvent(new AnalysisCompletedEvent(jobId, job.getStatus(), rootCategories,
                Duration.between(started, Instant.now())));
    }

    public void reject(UUID jobId, String reason) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.markFailed(reason);
            jobRepository.save(job);
            deleteIfConfigured(Path.of(job.getStoragePath()));
        });
    }

    /** Reconstructs every trace in parallel and merges edges / missing-response findings. */
    private ServiceCallGraph reconstructFlows(TraceAggregator traces, List<Finding> findings) {
        ServiceCallGraph graph = new ServiceCallGraph();
        List<TransactionFlow> flows = traces.traceIds().parallelStream()
                .map(id -> flowAnalyzer.analyze(id, traces.events(id)))
                .toList();
        for (TransactionFlow flow : flows) {
            flow.edges().forEach(graph::addEdge);
            flow.missingResponses().forEach(m -> findings.add(toFinding(m)));
        }
        return graph;
    }

    static Finding toFinding(MissingResponse m) {
        String message = "No timely response from " + m.callee() + " to " + m.caller() + " (" + m.reason() + ")";
        return Finding.builder()
                .category(FailureCategory.MISSING_RESPONSE)
                .severity(Severity.MEDIUM)
                .service(m.caller())
                .targetService(m.callee())
                .timestamp(m.requestedAt())
                .traceId(m.traceId())
                .message(message)
                .evidence(List.of(message))
                .detector("TransactionFlowAnalyzer")
                .build();
    }

    private String aiSummary(RcaResult result) {
        return summarizer.isAiBacked() ? summarizer.summarize(result) : null;
    }

    private static ServiceCallEdgeEntity toEntity(UUID jobId, CallEdge e) {
        ServiceCallEdgeEntity entity = new ServiceCallEdgeEntity();
        entity.setJobId(jobId);
        entity.setCaller(e.caller());
        entity.setCallee(e.callee());
        entity.setCallCount(e.callCount());
        entity.setErrorCount(e.errorCount());
        entity.setAvgLatencyMs(e.avgLatencyMs());
        entity.setMaxLatencyMs(e.maxLatencyMs());
        return entity;
    }

    private void deleteIfConfigured(Path file) {
        if (!properties.storage().deleteAfterProcessing()) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete processed upload {}: {}", file, e.getMessage());
        }
    }
}
