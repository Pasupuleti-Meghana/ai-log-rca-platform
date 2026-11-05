package com.logsense.reporting;

import com.logsense.domain.entity.IncidentEntity;
import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.JobStatus;
import com.logsense.domain.model.Severity;
import com.logsense.dto.DashboardDto;
import com.logsense.dto.IncidentSearchCriteria;
import com.logsense.dto.ServiceFailureCount;
import com.logsense.mapper.IncidentMapper;
import com.logsense.repository.AnalysisJobRepository;
import com.logsense.repository.IncidentRepository;
import com.logsense.repository.IncidentSpecifications;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dashboard aggregates computed with Streams {@code groupingBy}. For very large incident volumes this would move
 * to SQL GROUP BY / materialized views; incidents are a highly condensed dataset so in-memory is fine here.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final int TOP_N = 5;

    private final IncidentRepository incidentRepository;
    private final AnalysisJobRepository jobRepository;
    private final IncidentMapper mapper;

    public DashboardService(IncidentRepository incidentRepository, AnalysisJobRepository jobRepository,
                            IncidentMapper mapper) {
        this.incidentRepository = incidentRepository;
        this.jobRepository = jobRepository;
        this.mapper = mapper;
    }

    public DashboardDto dashboard(Instant from, Instant to) {
        List<IncidentEntity> incidents = incidentRepository.findAll(IncidentSpecifications.from(
                new IncidentSearchCriteria(null, null, null, null, from, to)));
        Map<JobStatus, Long> jobsByStatus = new EnumMap<>(JobStatus.class);
        Arrays.stream(JobStatus.values()).forEach(s -> jobsByStatus.put(s, jobRepository.countByStatus(s)));
        return build(incidents, jobRepository.count(), jobsByStatus);
    }

    DashboardDto build(List<IncidentEntity> incidents, long totalJobs, Map<JobStatus, Long> jobsByStatus) {
        Map<FailureCategory, Long> byCategory = incidents.stream()
                .filter(i -> i.getRootCauseCategory() != null)
                .collect(Collectors.groupingBy(IncidentEntity::getRootCauseCategory,
                        () -> new EnumMap<>(FailureCategory.class), Collectors.counting()));
        Map<Severity, Long> bySeverity = incidents.stream()
                .collect(Collectors.groupingBy(IncidentEntity::getSeverity,
                        () -> new EnumMap<>(Severity.class), Collectors.counting()));
        Map<String, Long> byRootService = sortedByValueDesc(incidents.stream()
                .filter(i -> i.getRootCauseService() != null)
                .collect(Collectors.groupingBy(IncidentEntity::getRootCauseService, Collectors.counting())));
        Map<String, Long> affectedCounts = incidents.stream()
                .flatMap(i -> i.getAffectedServices().stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<ServiceFailureCount> top = affectedCounts.keySet().stream()
                .map(s -> new ServiceFailureCount(s, byRootService.getOrDefault(s, 0L), affectedCounts.get(s)))
                .sorted(Comparator.comparingLong(ServiceFailureCount::rootCauseCount).reversed()
                        .thenComparing(Comparator.comparingLong(ServiceFailureCount::affectedCount).reversed())
                        .thenComparing(ServiceFailureCount::service))
                .limit(TOP_N)
                .toList();

        Double avgDuration = incidents.isEmpty() ? null
                : round(incidents.stream().mapToLong(IncidentEntity::getDurationMs).average().orElse(0));
        Long maxDuration = incidents.stream().map(IncidentEntity::getDurationMs).max(Long::compare).orElse(null);
        Double avgConfidence = incidents.isEmpty() ? null
                : round(incidents.stream().mapToDouble(IncidentEntity::getConfidence).average().orElse(0));
        List<com.logsense.dto.IncidentSummaryDto> recent = incidents.stream()
                .sorted(Comparator.comparing(IncidentEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(TOP_N)
                .map(mapper::toSummary)
                .toList();

        return new DashboardDto(totalJobs, jobsByStatus, incidents.size(), byCategory, bySeverity, byRootService,
                top, avgDuration, maxDuration, avgConfidence, recent);
    }

    private static Map<String, Long> sortedByValueDesc(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
