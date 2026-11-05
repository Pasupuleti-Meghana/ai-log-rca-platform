package com.logsense.processing.detection;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.domain.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static com.logsense.support.TestEvents.event;
import static com.logsense.support.TestEvents.withStack;
import static org.assertj.core.api.Assertions.assertThat;

class FailureDetectorsTest {

    private final DetectionEngine engine = DetectionEngine.withDefaultDetectors();

    @Test
    void chainIsOrderedFromMostSpecificToCatchAll() {
        assertThat(engine.chainOrder()).containsExactly("RetryExhaustionDetector", "DatabaseFailureDetector",
                "TimeoutDetector", "CommunicationFailureDetector", "ExceptionDetector");
    }

    @Test
    void exceptionDetectorCatchesStackTraces() {
        Optional<Finding> f = new ExceptionDetector().detect(withStack("order-service", "Unexpected error",
                "java.lang.NullPointerException: x is null\n\tat a.B.c(B.java:1)"));
        assertThat(f).isPresent();
        assertThat(f.get().category()).isEqualTo(FailureCategory.EXCEPTION);
        assertThat(f.get().exceptionClass()).isEqualTo("java.lang.NullPointerException");
        assertThat(f.get().evidence()).isNotEmpty();
    }

    @Test
    void timeoutDetectorFindsSocketTimeoutAndTargetService() {
        Optional<Finding> f = new TimeoutDetector().detect(withStack("order-service",
                "Read timed out calling inventory-service after 5000ms", "java.net.SocketTimeoutException: Read timed out"));
        assertThat(f).isPresent();
        assertThat(f.get().category()).isEqualTo(FailureCategory.TIMEOUT);
        assertThat(f.get().targetService()).isEqualTo("inventory-service");
    }

    @Test
    void timeoutDetectorFlagsGateway504Status() {
        ParsedLogEvent e = event("api-gateway", LogLevel.INFO, "POST /api/orders").withHttpStatus(504);
        assertThat(new TimeoutDetector().detect(e)).map(Finding::category).contains(FailureCategory.TIMEOUT);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "HikariPool-1 - Connection is not available, request timed out after 30000ms.",
            "HikariPool-1 - Connection pool exhausted (total=10, active=10, idle=0, waiting=37)",
            "ERROR: deadlock detected",
            "org.springframework.dao.DataIntegrityViolationException: duplicate key value violates unique constraint",
            "java.sql.SQLException: Connection closed"})
    void databaseDetectorRecognisesDbFailures(String message) {
        Optional<Finding> f = engine.detect(event("inventory-service", LogLevel.ERROR, message));
        assertThat(f).map(Finding::category).contains(FailureCategory.DATABASE);
    }

    @Test
    void poolExhaustionIsAtLeastHighSeverityEvenWhenLoggedAsWarn() {
        Finding f = new DatabaseFailureDetector().detect(event("inventory-service", LogLevel.WARN,
                "HikariPool-1 - Connection pool exhausted")).orElseThrow();
        assertThat(f.severity()).isGreaterThanOrEqualTo(Severity.HIGH);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "I/O error on POST request: Connection refused; nested exception is java.net.ConnectException",
            "Payment call failed: 503 SERVICE_UNAVAILABLE from payment-service",
            "CircuitBreaker 'inventory' is OPEN and does not permit further calls",
            "java.net.UnknownHostException: pricing-service: Name or service not known",
            "upstream returned 502 Bad Gateway"})
    void communicationDetectorRecognisesNetworkFailures(String message) {
        assertThat(engine.detect(event("order-service", LogLevel.ERROR, message)))
                .map(Finding::category).contains(FailureCategory.COMMUNICATION);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Retry attempt 3/3 for fraud-check-service failed: java.net.ConnectException: Connection refused",
            "io.github.resilience4j.retry.MaxRetriesExceededException: Retries exhausted after 3 attempts",
            "Max retries exceeded for call to fraud-check-service",
            "Giving up after 5 attempts"})
    void retryExhaustionDetectorWinsOverUnderlyingCause(String message) {
        assertThat(engine.detect(event("payment-service", LogLevel.ERROR, message)))
                .map(Finding::category).contains(FailureCategory.RETRY_EXHAUSTION);
    }

    @Test
    void intermediateRetryAttemptIsNotExhaustion() {
        Finding f = engine.detect(event("payment-service", LogLevel.WARN,
                "Retry attempt 1/3 for fraud-check-service failed: java.net.ConnectException: Connection refused"))
                .orElseThrow();
        assertThat(f.category()).isEqualTo(FailureCategory.COMMUNICATION);
        assertThat(f.targetService()).isEqualTo("fraud-check-service");
    }

    @Test
    void infoLinesWithoutErrorsProduceNoFinding() {
        assertThat(engine.detect(event("order-service", LogLevel.INFO, "Order created, retry policy configured")))
                .isEmpty();
        assertThat(engine.detectAll(List.of(event("a-service", LogLevel.DEBUG, "Connection refused (expected in test)"))))
                .isEmpty();
    }
}