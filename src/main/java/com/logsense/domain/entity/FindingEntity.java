package com.logsense.domain.entity;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finding")
@Getter
@Setter
@NoArgsConstructor
public class FindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private IncidentEntity incident;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FailureCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(length = 128)
    private String service;

    @Column(name = "target_service", length = 128)
    private String targetService;

    @Column(name = "event_timestamp")
    private Instant timestamp;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "line_number")
    private long lineNumber;

    @Column(name = "exception_class", length = 256)
    private String exceptionClass;

    @Column(length = 4000)
    private String message;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(length = 64)
    private String detector;
}
