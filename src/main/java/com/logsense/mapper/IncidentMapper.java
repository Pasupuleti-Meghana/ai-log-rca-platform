package com.logsense.mapper;

import com.logsense.domain.entity.FindingEntity;
import com.logsense.domain.entity.IncidentEntity;
import com.logsense.domain.entity.TimelineEntry;
import com.logsense.domain.model.Finding;
import com.logsense.dto.FindingDto;
import com.logsense.dto.IncidentDetailDto;
import com.logsense.dto.IncidentSummaryDto;
import com.logsense.dto.TimelineEntryDto;
import com.logsense.rca.RcaResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class IncidentMapper {

    private static final int MAX_MESSAGE = 4000;

    public IncidentEntity toEntity(UUID jobId, RcaResult r, String aiSummary, int maxFindingsPerIncident) {
        IncidentEntity e = new IncidentEntity();
        e.setJobId(jobId);
        e.setTitle(truncate(r.title(), 512));
        e.setRootCauseService(r.rootCauseService());
        e.setRootCauseCategory(r.rootCauseCategory());
        e.setSeverity(r.severity());
        e.setConfidence(r.confidence());
        e.setSummary(r.summary());
        e.setAiSummary(aiSummary);
        e.setCausalChain(new ArrayList<>(r.causalChain()));
        e.setAffectedServices(new ArrayList<>(r.affectedServices()));
        e.setTraceIds(new ArrayList<>(r.traceIds()));
        e.setFirstFailureAt(r.firstFailureAt());
        e.setLastFailureAt(r.lastFailureAt());
        e.setDurationMs(r.durationMs());
        e.setFindingCount(r.findings().size());
        e.setRecommendations(new ArrayList<>(r.recommendations()));
        e.setTimeline(new ArrayList<>(r.timeline().stream()
                .map(t -> new TimelineEntry(t.occurredAt(), t.offsetMs(), t.service(), t.category(), t.severity(),
                        truncate(t.description(), 1000)))
                .toList()));
        r.findings().stream().limit(maxFindingsPerIncident).forEach(f -> e.addFinding(toEntity(jobId, f)));
        return e;
    }

    FindingEntity toEntity(UUID jobId, Finding f) {
        FindingEntity fe = new FindingEntity();
        fe.setJobId(jobId);
        fe.setCategory(f.category());
        fe.setSeverity(f.severity());
        fe.setService(f.service());
        fe.setTargetService(f.targetService());
        fe.setTimestamp(f.timestamp());
        fe.setTraceId(truncate(f.traceId(), 64));
        fe.setLineNumber(f.lineNumber());
        fe.setExceptionClass(truncate(f.exceptionClass(), 256));
        fe.setMessage(truncate(f.message(), MAX_MESSAGE));
        fe.setEvidence(String.join("\n", f.evidence()));
        fe.setDetector(f.detector());
        return fe;
    }

    public IncidentSummaryDto toSummary(IncidentEntity e) {
        return new IncidentSummaryDto(e.getId(), e.getJobId(), e.getTitle(), e.getRootCauseService(),
                e.getRootCauseCategory(), e.getSeverity(), e.getConfidence(), List.copyOf(e.getAffectedServices()),
                e.getFirstFailureAt(), e.getDurationMs(), e.getFindingCount(), e.getCreatedAt());
    }

    public IncidentDetailDto toDetail(IncidentEntity e) {
        return new IncidentDetailDto(e.getId(), e.getJobId(), e.getTitle(), e.getRootCauseService(),
                e.getRootCauseCategory(), e.getSeverity(), e.getConfidence(), e.getSummary(), e.getAiSummary(),
                List.copyOf(e.getCausalChain()), List.copyOf(e.getAffectedServices()), List.copyOf(e.getTraceIds()),
                e.getFirstFailureAt(), e.getLastFailureAt(), e.getDurationMs(), e.getFindingCount(),
                e.getTimeline().stream().map(this::toDto).toList(),
                e.getFindings().stream().map(this::toDto).toList(),
                List.copyOf(e.getRecommendations()), e.getCreatedAt());
    }

    private TimelineEntryDto toDto(TimelineEntry t) {
        return new TimelineEntryDto(t.getOccurredAt(), t.getOffsetMs(), t.getService(), t.getCategory(),
                t.getSeverity(), t.getDescription());
    }

    private FindingDto toDto(FindingEntity f) {
        List<String> evidence = f.getEvidence() == null || f.getEvidence().isEmpty()
                ? List.of() : List.of(f.getEvidence().split("\n"));
        return new FindingDto(f.getId(), f.getCategory(), f.getSeverity(), f.getService(), f.getTargetService(),
                f.getTimestamp(), f.getTraceId(), f.getLineNumber(), f.getExceptionClass(), f.getMessage(), evidence,
                f.getDetector());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
