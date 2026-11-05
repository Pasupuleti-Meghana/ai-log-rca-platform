package com.logsense.domain.entity;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "incident")
@Getter
@Setter
@NoArgsConstructor
public class IncidentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(name = "root_cause_service", length = 128)
    private String rootCauseService;

    @Enumerated(EnumType.STRING)
    @Column(name = "root_cause_category", length = 32)
    private FailureCategory rootCauseCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false)
    private double confidence;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Convert(converter = StringListConverter.class)
    @Column(name = "causal_chain", length = 1024)
    private List<String> causalChain = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "affected_services", length = 1024)
    private List<String> affectedServices = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "trace_ids", length = 2000)
    private List<String> traceIds = new ArrayList<>();

    @Column(name = "first_failure_at")
    private Instant firstFailureAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(name = "finding_count")
    private int findingCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("timestamp ASC")
    private List<FindingEntity> findings = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "incident_timeline", joinColumns = @JoinColumn(name = "incident_id"))
    @OrderColumn(name = "item_order")
    private List<TimelineEntry> timeline = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "incident_recommendation", joinColumns = @JoinColumn(name = "incident_id"))
    @OrderColumn(name = "item_order")
    @Column(name = "recommendation", length = 1000)
    private List<String> recommendations = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void addFinding(FindingEntity finding) {
        finding.setIncident(this);
        findings.add(finding);
    }
}
