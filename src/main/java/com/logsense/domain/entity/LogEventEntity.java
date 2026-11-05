package com.logsense.domain.entity;

import com.logsense.domain.model.LogLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted log event. Uses a pooled sequence (allocationSize = 50) so Hibernate can JDBC-batch inserts
 * (IDENTITY generation would disable batching).
 */
@Entity
@Table(name = "log_event")
@Getter
@Setter
@NoArgsConstructor
public class LogEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "log_event_seq")
    @SequenceGenerator(name = "log_event_seq", sequenceName = "log_event_seq", allocationSize = 50)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "line_number")
    private long lineNumber;

    @Column(name = "event_timestamp")
    private Instant timestamp;

    @Column(length = 128)
    private String service;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private LogLevel level;

    @Column(length = 128)
    private String thread;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "span_id", length = 64)
    private String spanId;

    @Column(name = "parent_span_id", length = 64)
    private String parentSpanId;

    @Column(length = 4000)
    private String message;

    @Column(name = "exception_class", length = 256)
    private String exceptionClass;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "source_format", length = 32)
    private String sourceFormat;
}
