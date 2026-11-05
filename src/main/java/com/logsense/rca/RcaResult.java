package com.logsense.rca;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.Severity;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Output of the RCA engine for one incident (cluster of related findings).
 *
 * @param causalChain           services from the outermost impacted service down to the root cause
 * @param rootTargetDependency  dependency the root service was calling when it failed (may be external/unlogged)
 */
@Builder(toBuilder = true)
public record RcaResult(
        String title,
        String rootCauseService,
        FailureCategory rootCauseCategory,
        double confidence,
        Severity severity,
        List<String> causalChain,
        List<String> affectedServices,
        Instant firstFailureAt,
        Instant lastFailureAt,
        long durationMs,
        List<Finding> findings,
        List<TimelineItem> timeline,
        List<String> recommendations,
        List<String> rootEvidence,
        List<String> traceIds,
        String rootTargetDependency,
        String summary) {
}
