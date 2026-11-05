package com.logsense.processing.ingestion;

import com.logsense.config.LogSenseProperties;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.processing.detection.DetectionEngine;
import com.logsense.processing.flow.TraceAggregator;
import com.logsense.processing.masking.PiiMasker;
import com.logsense.processing.parser.LogEventAssembler;
import com.logsense.processing.parser.LogParserFactory;
import com.logsense.processing.parser.ParseContext;
import com.logsense.service.LogSenseMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Streaming ingestion:
 * <pre>
 * BufferedReader (line by line, never whole file)
 *   -> LogEventAssembler (format auto-detect per line, multi-line stack traces)
 *   -> chunks of N events -> chunkExecutor (bounded queue, CallerRunsPolicy = backpressure)
 *        -> PII masking -> DetectionEngine -> TraceAggregator (ConcurrentHashMap) -> batch insert
 * </pre>
 * Chunks are independent, so they can be processed in parallel; per-trace ordering is restored later by sorting
 * on timestamp/line number.
 */
@Component
public class LogIngestionPipeline {

    private static final int PROGRESS_CHECK_INTERVAL = 2048;
    private static final int MAX_PENDING_FUTURES = 64;

    private final LogParserFactory parserFactory;
    private final DetectionEngine detectionEngine;
    private final PiiMasker masker;
    private final EventSink sink;
    private final Executor chunkExecutor;
    private final LogSenseProperties.Processing config;
    private final LogSenseMetrics metrics;

    public LogIngestionPipeline(LogParserFactory parserFactory, DetectionEngine detectionEngine, PiiMasker masker,
                                EventSink sink, @Qualifier("chunkExecutor") Executor chunkExecutor,
                                LogSenseProperties properties, LogSenseMetrics metrics) {
        this.parserFactory = parserFactory;
        this.detectionEngine = detectionEngine;
        this.masker = masker;
        this.sink = sink;
        this.chunkExecutor = chunkExecutor;
        this.config = properties.processing();
        this.metrics = metrics;
    }

    public IngestionResult ingest(UUID jobId, InputStream input, long totalBytes, ParseContext context,
                                  IntConsumer progressListener) throws IOException {
        TraceAggregator traces = new TraceAggregator(config.maxTraces(), config.maxEventsPerTrace());
        FindingCollector findings = new FindingCollector(config.maxFindings());
        AtomicLong parsed = new AtomicLong();
        LogEventAssembler assembler = new LogEventAssembler(parserFactory, context);
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        List<ParsedLogEvent> batch = new ArrayList<>(config.batchSize());
        long lineNumber = 0;
        int lastProgress = -1;

        CountingInputStream counting = new CountingInputStream(input);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(counting, StandardCharsets.UTF_8), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Optional<ParsedLogEvent> completed = assembler.accept(line, lineNumber);
                if (completed.isPresent()) {
                    batch.add(completed.get());
                    if (batch.size() >= config.batchSize()) {
                        pending.add(submit(jobId, batch, findings, traces, parsed));
                        batch = new ArrayList<>(config.batchSize());
                        reapCompleted(pending);
                    }
                }
                if (lineNumber % PROGRESS_CHECK_INTERVAL == 0 && totalBytes > 0) {
                    int progress = (int) Math.min(95, counting.getCount() * 95 / totalBytes);
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        progressListener.accept(progress);
                    }
                }
            }
        }
        assembler.flush().ifPresent(batch::add);
        if (!batch.isEmpty()) {
            pending.add(submit(jobId, batch, findings, traces, parsed));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();

        return new IngestionResult(lineNumber, parsed.get(), assembler.unparsedLines(), assembler.dominantFormat(),
                findings.snapshot(), findings.total(), traces);
    }

    private CompletableFuture<Void> submit(UUID jobId, List<ParsedLogEvent> chunk, FindingCollector findings,
                                           TraceAggregator traces, AtomicLong parsed) {
        return CompletableFuture.runAsync(() -> processChunk(jobId, chunk, findings, traces, parsed), chunkExecutor);
    }

    void processChunk(UUID jobId, List<ParsedLogEvent> chunk, FindingCollector findings, TraceAggregator traces,
                      AtomicLong parsed) {
        List<ParsedLogEvent> masked = new ArrayList<>(chunk.size());
        int detected = 0;
        for (ParsedLogEvent raw : chunk) {
            ParsedLogEvent event = masker.mask(raw);
            masked.add(event);
            Optional<Finding> finding = detectionEngine.detect(event);
            if (finding.isPresent()) {
                findings.add(finding.get());
                detected++;
            }
            traces.accept(event);
        }
        sink.saveBatch(jobId, masked);
        parsed.addAndGet(chunk.size());
        metrics.logsParsed(chunk.size());
        metrics.findingsDetected(detected);
    }

    /** Fails fast if a chunk failed and keeps the futures list small for very large files. */
    private static void reapCompleted(List<CompletableFuture<Void>> pending) {
        if (pending.size() < MAX_PENDING_FUTURES) {
            return;
        }
        for (CompletableFuture<Void> f : pending) {
            if (f.isCompletedExceptionally()) {
                f.join();
            }
        }
        pending.removeIf(CompletableFuture::isDone);
    }

    /** Thread-safe, bounded collector. */
    static final class FindingCollector {
        private final ConcurrentLinkedQueue<Finding> retained = new ConcurrentLinkedQueue<>();
        private final AtomicInteger retainedCount = new AtomicInteger();
        private final AtomicLong total = new AtomicLong();
        private final int max;

        FindingCollector(int max) {
            this.max = max;
        }

        void add(Finding finding) {
            total.incrementAndGet();
            if (retainedCount.incrementAndGet() <= max) {
                retained.add(finding);
            }
        }

        List<Finding> snapshot() {
            return List.copyOf(retained);
        }

        long total() {
            return total.get();
        }
    }
}
