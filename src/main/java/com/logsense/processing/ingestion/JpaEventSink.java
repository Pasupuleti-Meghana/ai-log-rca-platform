package com.logsense.processing.ingestion;

import com.logsense.domain.entity.LogEventEntity;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.repository.LogEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Persists one chunk per transaction via {@code saveAll}; with {@code hibernate.jdbc.batch_size} and a pooled
 * sequence this becomes JDBC batch inserts. The persistence context is discarded after each chunk, so memory stays
 * flat regardless of file size.
 */
@Component
public class JpaEventSink implements EventSink {

    private static final int MAX_MESSAGE = 4000;

    private final LogEventRepository repository;
    private final TransactionTemplate tx;

    public JpaEventSink(LogEventRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void saveBatch(UUID jobId, List<ParsedLogEvent> events) {
        List<LogEventEntity> entities = events.stream().map(e -> toEntity(jobId, e)).toList();
        tx.executeWithoutResult(status -> repository.saveAll(entities));
    }

    static LogEventEntity toEntity(UUID jobId, ParsedLogEvent e) {
        LogEventEntity entity = new LogEventEntity();
        entity.setJobId(jobId);
        entity.setLineNumber(e.lineNumber());
        entity.setTimestamp(e.timestamp());
        entity.setService(truncate(e.service(), 128));
        entity.setLevel(e.level());
        entity.setThread(truncate(e.thread(), 128));
        entity.setTraceId(truncate(e.traceId(), 64));
        entity.setCorrelationId(truncate(e.correlationId(), 128));
        entity.setSpanId(truncate(e.spanId(), 64));
        entity.setParentSpanId(truncate(e.parentSpanId(), 64));
        entity.setMessage(truncate(e.message(), MAX_MESSAGE));
        entity.setExceptionClass(truncate(e.exceptionClass(), 256));
        entity.setStackTrace(e.stackTrace());
        entity.setHttpStatus(e.httpStatus());
        entity.setDurationMs(e.durationMs());
        entity.setSourceFormat(e.format());
        return entity;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
