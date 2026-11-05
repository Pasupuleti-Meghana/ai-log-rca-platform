package com.logsense.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "service_call_edge")
@Getter
@Setter
@NoArgsConstructor
public class ServiceCallEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false, length = 128)
    private String caller;

    @Column(nullable = false, length = 128)
    private String callee;

    @Column(name = "call_count", nullable = false)
    private long callCount;

    @Column(name = "error_count", nullable = false)
    private long errorCount;

    @Column(name = "avg_latency_ms")
    private Double avgLatencyMs;

    @Column(name = "max_latency_ms")
    private Long maxLatencyMs;
}
