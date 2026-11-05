package com.logsense.processing.ingestion;

import com.logsense.config.LogSenseProperties;
import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.processing.detection.DetectionEngine;
import com.logsense.processing.masking.PiiMasker;
import com.logsense.processing.parser.LogParserFactory;
import com.logsense.processing.parser.ParseContext;
import com.logsense.service.LogSenseMetrics;
import com.logsense.tools.LogGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogIngestionPipelineTest {

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.CallerRunsPolicy());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private LogIngestionPipeline pipeline(EventSink sink) {
        LogSenseProperties props = new LogSenseProperties(null,
                new LogSenseProperties.Processing(1000, 2, 2, 0, 0, 0, null, null), null, null);
        return new LogIngestionPipeline(LogParserFactory.withDefaultParsers(), DetectionEngine.withDefaultDetectors(),
                new PiiMasker(), sink, executor, props, new LogSenseMetrics(registry));
    }

    @Test
    void streamsLargeFileInParallelChunksWithBatchedPersistence() throws Exception {
        int lines = 50_000;
        StringWriter writer = new StringWriter();
        LogGenerator.generate(writer, lines, 7L);
        byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);

        AtomicLong saved = new AtomicLong();
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        List<Integer> progress = new CopyOnWriteArrayList<>();
        EventSink sink = (jobId, events) -> {
            saved.addAndGet(events.size());
            batchSizes.add(events.size());
        };

        IngestionResult result = pipeline(sink).ingest(UUID.randomUUID(), new ByteArrayInputStream(bytes),
                bytes.length, ParseContext.defaults(), progress::add);

        assertThat(result.totalLines()).isEqualTo(lines);
        assertThat(result.parsedEvents()).isEqualTo(lines);
        assertThat(result.unparsedLines()).isZero();
        assertThat(result.detectedFormat()).isEqualTo("SPRING_BOOT");
        assertThat(saved.get()).isEqualTo(lines);
        assertThat(batchSizes).allMatch(size -> size <= 1000);
        assertThat(result.traces().traceCount()).isGreaterThan(5000);
        assertThat(result.findings()).extracting(Finding::category)
                .contains(FailureCategory.DATABASE, FailureCategory.TIMEOUT, FailureCategory.RETRY_EXHAUSTION);
        assertThat(result.totalFindings()).isEqualTo(result.findings().size());
        assertThat(progress).isNotEmpty().allMatch(p -> p >= 0 && p <= 95);
        assertThat(registry.get("logsense.logs.parsed").counter().count()).isEqualTo(lines);
    }

    @Test
    void masksPiiBeforePersistenceAndDetection() throws Exception {
        String log = "2024-10-15 11:20:00,101 [exec-3] ERROR com.acme.Pay - service=payment-service traceId=ab12cd34ef56ab78 "
                + "Payment failed for john.doe@example.com card=4111 1111 1111 1111: java.net.ConnectException: Connection refused\n";
        List<ParsedLogEvent> stored = new CopyOnWriteArrayList<>();
        IngestionResult result = pipeline((jobId, events) -> stored.addAll(events)).ingest(UUID.randomUUID(),
                new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)), log.length(),
                ParseContext.defaults(), p -> { });

        assertThat(stored).singleElement().satisfies(e -> {
            assertThat(e.message()).contains("[EMAIL]").contains("[CARD]").doesNotContain("john.doe");
            assertThat(e.service()).isEqualTo("payment-service");
        });
        assertThat(result.findings()).singleElement()
                .satisfies(f -> assertThat(f.message()).doesNotContain("4111"));
    }
}