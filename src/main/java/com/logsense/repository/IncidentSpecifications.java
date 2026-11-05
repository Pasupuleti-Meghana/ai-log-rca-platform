package com.logsense.repository;

import com.logsense.domain.entity.FindingEntity;
import com.logsense.domain.entity.IncidentEntity;
import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;
import com.logsense.dto.IncidentSearchCriteria;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/** Composable JPA Specifications for incident history filtering. */
public final class IncidentSpecifications {

    private IncidentSpecifications() {
    }

    public static Specification<IncidentEntity> from(IncidentSearchCriteria c) {
        return Specification.allOf(
                involvesService(c.service()),
                hasSeverity(c.severity()),
                hasCategory(c.category()),
                belongsToJob(c.jobId()),
                occurredFrom(c.from()),
                occurredTo(c.to()));
    }

    /** Root cause service OR any service with a finding in the incident. */
    public static Specification<IncidentEntity> involvesService(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<FindingEntity> f = sub.from(FindingEntity.class);
            sub.select(cb.literal(1L)).where(cb.equal(f.get("incident"), root), cb.equal(f.get("service"), service));
            return cb.or(cb.equal(root.get("rootCauseService"), service), cb.exists(sub));
        };
    }

    public static Specification<IncidentEntity> hasSeverity(Severity severity) {
        return severity == null ? null : (root, q, cb) -> cb.equal(root.get("severity"), severity);
    }

    public static Specification<IncidentEntity> hasCategory(FailureCategory category) {
        return category == null ? null : (root, q, cb) -> cb.equal(root.get("rootCauseCategory"), category);
    }

    public static Specification<IncidentEntity> belongsToJob(UUID jobId) {
        return jobId == null ? null : (root, q, cb) -> cb.equal(root.get("jobId"), jobId);
    }

    public static Specification<IncidentEntity> occurredFrom(Instant from) {
        return from == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("firstFailureAt"), from);
    }

    public static Specification<IncidentEntity> occurredTo(Instant to) {
        return to == null ? null : (root, q, cb) -> cb.lessThanOrEqualTo(root.get("firstFailureAt"), to);
    }
}
