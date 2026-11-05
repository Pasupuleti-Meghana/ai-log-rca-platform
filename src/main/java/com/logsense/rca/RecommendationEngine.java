package com.logsense.rca;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based remediation advice keyed by failure category. Specific rules (pattern triggered) for the root cause
 * come first, then the generic advice for the root category, then one hint per symptom category.
 */
@Component
public class RecommendationEngine {

    static final int MAX_RECOMMENDATIONS = 10;

    private final List<RecommendationRule> rules = List.of(
            rule(FailureCategory.DATABASE,
                    "(?i)hikari|connection is not available|pool exhausted|unable to acquire jdbc|too many connections",
                    "Check for connection leaks: enable HikariCP leakDetectionThreshold and make sure connections/transactions are always closed.",
                    "Identify slow or long-running queries holding connections (pg_stat_statements / slow query log) and add missing indexes.",
                    "Right-size spring.datasource.hikari.maximum-pool-size against database capacity instead of blindly increasing it.",
                    "Set the pool connection-timeout lower than upstream HTTP timeouts so the failure surfaces fast instead of cascading."),
            rule(FailureCategory.DATABASE, "(?i)deadlock|could not serialize|LockAcquisition|lock wait timeout",
                    "Acquire row locks in a consistent order across transactions to prevent deadlocks.",
                    "Keep transactions short and move remote calls outside transactional boundaries.",
                    "Retry deadlock victims (SQLState 40P01/40001) with bounded exponential backoff."),
            rule(FailureCategory.DATABASE, "(?i)constraint|duplicate key|DataIntegrityViolation",
                    "Validate input before persistence and use idempotency keys to avoid duplicate inserts.",
                    "Map DataIntegrityViolationException to 409 Conflict instead of a 500 error."),
            rule(FailureCategory.DATABASE, null,
                    "Check database health (CPU, active connections, locks, replication lag) at the incident start time.",
                    "Alert on connection-pool saturation (hikaricp_connections_pending) and DB error rates."),
            rule(FailureCategory.TIMEOUT, null,
                    "Align timeout budgets: each downstream timeout must be shorter than its caller's (gateway > service > database).",
                    "Wrap the slow dependency with a Resilience4j circuit breaker and bulkhead to fail fast.",
                    "Investigate downstream p99 latency and add tracing spans around slow operations."),
            rule(FailureCategory.COMMUNICATION, "(?i)circuit|CallNotPermitted",
                    "The circuit breaker is open: inspect the health of the protected dependency before tuning thresholds."),
            rule(FailureCategory.COMMUNICATION, "(?i)UnknownHost|resolve|DNS|Name or service not known",
                    "Verify service discovery / DNS records (Kubernetes Service names, Eureka registrations)."),
            rule(FailureCategory.COMMUNICATION, null,
                    "Verify the target service is running and its readiness probe passes (connection refused / 502 / 503).",
                    "Use retries with exponential backoff and jitter for transient network errors.",
                    "Provide fallbacks or graceful degradation for non-critical dependencies."),
            rule(FailureCategory.RETRY_EXHAUSTION, null,
                    "Retries are masking a persistent failure: fix the failing dependency instead of raising max attempts.",
                    "Use exponential backoff with jitter and keep total retry time below the caller's timeout.",
                    "Combine retry with a circuit breaker to prevent retry storms against an unhealthy dependency.",
                    "Make retried operations idempotent (idempotency keys) and route exhausted requests to a dead-letter queue."),
            rule(FailureCategory.MISSING_RESPONSE, null,
                    "Ensure every request path logs a response or error with the traceId (structured access logs).",
                    "Check the callee for crashed or OOM-killed pods and dropped connections at that time."),
            rule(FailureCategory.EXCEPTION, "(?i)OutOfMemoryError",
                    "Capture a heap dump (-XX:+HeapDumpOnOutOfMemoryError) and analyse it for leaks.",
                    "Review container memory limits versus heap sizing (-XX:MaxRAMPercentage)."),
            rule(FailureCategory.EXCEPTION, "(?i)NullPointerException",
                    "Add null-safety and input validation on the failing code path."),
            rule(FailureCategory.EXCEPTION, null,
                    "Inspect the stack trace of the first occurrence and add a regression test for the failing code path.",
                    "Map exceptions to meaningful HTTP status codes and log them once with full context."));

    public List<String> recommend(FailureCategory rootCategory, List<Finding> rootFindings, List<Finding> allFindings) {
        Set<String> result = new LinkedHashSet<>();
        rules.stream()
                .filter(r -> r.category() == rootCategory && !r.isGeneric())
                .filter(r -> rootFindings.stream().anyMatch(r::appliesTo))
                .forEach(r -> result.addAll(r.recommendations()));
        genericFor(rootCategory).ifPresent(r -> result.addAll(r.recommendations()));
        allFindings.stream()
                .map(Finding::category)
                .filter(c -> c != rootCategory)
                .distinct()
                .sorted(Comparator.comparingInt(FailureCategory::rootCauseWeight).reversed())
                .forEach(c -> genericFor(c).ifPresent(r -> result.add(r.recommendations().getFirst())));
        return result.stream().limit(MAX_RECOMMENDATIONS).toList();
    }

    private java.util.Optional<RecommendationRule> genericFor(FailureCategory category) {
        return rules.stream().filter(r -> r.category() == category && r.isGeneric()).findFirst();
    }

    private static RecommendationRule rule(FailureCategory category, String regex, String... recommendations) {
        return new RecommendationRule(category, regex == null ? null : Pattern.compile(regex), List.of(recommendations));
    }
}
