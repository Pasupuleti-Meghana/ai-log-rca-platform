package com.logsense.domain.entity;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEntry {

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "offset_ms")
    private long offsetMs;

    @Column(length = 128)
    private String service;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FailureCategory category;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Severity severity;

    @Column(length = 1000)
    private String description;
}
