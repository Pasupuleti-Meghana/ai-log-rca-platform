package com.logsense.dto;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.JobStatus;
import com.logsense.domain.model.Severity;

import java.util.List;
import java.util.Map;

/**
 * @param averageIncidentDurationMs MTTR-like metric: mean time between first and last failure signal per incident
 */
public record DashboardDto(
        long totalJobs,
        Map<JobStatus, Long> jobsByStatus,
        long totalIncidents,
        Map<FailureCategory, Long> incidentsByCategory,
        Map<Severity, Long> incidentsBySeverity,
        Map<String, Long> incidentsByRootCauseService,
        List<ServiceFailureCount> topFailingServices,
        Double averageIncidentDurationMs,
        Long maxIncidentDurationMs,
        Double averageConfidence,
        List<IncidentSummaryDto> recentIncidents) {
}
