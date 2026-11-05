package com.logsense.domain.entity;

import com.logsense.domain.model.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analysis_job")
@Getter
@Setter
@NoArgsConstructor
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status = JobStatus.QUEUED;

    @Column(nullable = false)
    private int progress;

    @Column(name = "detected_format", length = 32)
    private String detectedFormat;

    @Column(name = "total_lines", nullable = false)
    private long totalLines;

    @Column(name = "parsed_events", nullable = false)
    private long parsedEvents;

    @Column(name = "unparsed_lines", nullable = false)
    private long unparsedLines;

    @Column(name = "findings_count", nullable = false)
    private int findingsCount;

    @Column(name = "incidents_count", nullable = false)
    private int incidentsCount;

    @Column(name = "trace_count", nullable = false)
    private int traceCount;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public AnalysisJob(String fileName, long fileSize, String storagePath) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.storagePath = storagePath;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void markProcessing() {
        this.status = JobStatus.PROCESSING;
        this.startedAt = Instant.now();
        this.progress = 0;
    }

    public void markCompleted() {
        this.status = JobStatus.COMPLETED;
        this.progress = 100;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = JobStatus.FAILED;
        this.completedAt = Instant.now();
        this.errorMessage = reason == null ? "Unknown error"
                : reason.substring(0, Math.min(reason.length(), 2000));
    }
}
