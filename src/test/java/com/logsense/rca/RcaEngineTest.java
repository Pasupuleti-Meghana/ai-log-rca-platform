package com.logsense.rca;

import com.logsense.ai.TemplateSummarizer;
import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.Severity;
import com.logsense.processing.flow.ServiceCallGraph;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.logsense.domain.model.FailureCategory.COMMUNICATION;
import static com.logsense.domain.model.FailureCategory.DATABASE;
import static com.logsense.domain.model.FailureCategory.EXCEPTION;
import static com.logsense.domain.model.FailureCategory.RETRY_EXHAUSTION;
import static com.logsense.domain.model.FailureCategory.TIMEOUT;
import static com.logsense.support.TestEvents.finding;
import static org.assertj.core.api.Assertions.assertThat;

class RcaEngineTest {

    private final RcaEngine engine = new RcaEngine(new RecommendationEngine(), new TemplateSummarizer(),
            Duration.ofMinutes(5));

    private static ServiceCallGraph cascadeGraph() {
        ServiceCallGraph graph = new ServiceCallGraph();
        graph.addCall("api-gateway", "order-service", 120L, true);
        graph.addCall("order-service", "inventory-service", 5000L, true);
        return graph;
    }

    @Test
    void cascadeRootCauseIsDeepestFailingDownstreamDatabase() {
        List<Finding> findings = List.of(
                finding("inventory-service", DATABASE, Severity.HIGH, 900, null),
                finding("order-service", TIMEOUT, Severity.HIGH, 5200, "inventory-service"),
                finding("api-gateway", TIMEOUT, Severity.HIGH, 5260, "order-service"),
                finding("inventory-service", DATABASE, Severity.CRITICAL, 30210, null));

        List<RcaResult> results = engine.analyze(findings, cascadeGraph());

        assertThat(results).hasSize(1);
        RcaResult rca = results.getFirst();
        assertThat(rca.rootCauseService()).isEqualTo("inventory-service");
        assertThat(rca.rootCauseCategory()).isEqualTo(DATABASE);
        assertThat(rca.causalChain()).containsExactly("api-gateway", "order-service", "inventory-service");
        assertThat(rca.confidence()).isGreaterThanOrEqualTo(0.9);
        assertThat(rca.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(rca.affectedServices()).containsExactly("inventory-service", "order-service", "api-gateway");
        assertThat(rca.durationMs()).isEqualTo(29310);
        assertThat(rca.timeline()).first().satisfies(t -> {
            assertThat(t.offsetMs()).isZero();
            assertThat(t.service()).isEqualTo("inventory-service");
        });
        assertThat(rca.recommendations()).isNotEmpty();
        assertThat(rca.summary()).startsWith("inventory-service is the most likely root cause");
    }

    @Test
    void downstreamServiceIsRootEvenWhenSymptomIsLoggedFirst() {
        // gateway logs its timeout before inventory flushes its DB error: graph position must beat timestamps
        List<Finding> findings = List.of(
                finding("api-gateway", TIMEOUT, Severity.HIGH, 0, "order-service"),
                finding("order-service", TIMEOUT, Severity.HIGH, 100, "inventory-service"),
                finding("inventory-service", DATABASE, Severity.HIGH, 400, null));

        RcaResult rca = engine.analyze(findings, new ServiceCallGraph()).getFirst();

        assertThat(rca.rootCauseService()).isEqualTo("inventory-service");
        assertThat(rca.causalChain()).containsExactly("api-gateway", "order-service", "inventory-service");
        assertThat(rca.confidence()).isLessThan(0.9).isGreaterThan(0.5);
    }

    @Test
    void retryExhaustionAgainstUnloggedDependencyBlamesCallerAndReportsDependency() {
        List<Finding> findings = List.of(
                finding("payment-service", COMMUNICATION, Severity.MEDIUM, 130, "fraud-check-service"),
                finding("payment-service", RETRY_EXHAUSTION, Severity.HIGH, 3150, "fraud-check-service"),
                finding("order-service", COMMUNICATION, Severity.HIGH, 3170, "payment-service"));

        RcaResult rca = engine.analyze(findings, new ServiceCallGraph()).getFirst();

        assertThat(rca.rootCauseService()).isEqualTo("payment-service");
        assertThat(rca.rootCauseCategory()).isEqualTo(RETRY_EXHAUSTION);
        assertThat(rca.rootTargetDependency()).isEqualTo("fraud-check-service");
        assertThat(rca.causalChain()).containsExactly("order-service", "payment-service");
        assertThat(rca.summary()).contains("while calling fraud-check-service");
    }

    @Test
    void independentFailuresWithoutGraphPreferEarliestAndLowerConfidence() {
        List<Finding> findings = List.of(
                finding("billing-service", EXCEPTION, Severity.HIGH, 0, null),
                finding("search-service", EXCEPTION, Severity.HIGH, 1000, null));

        RcaResult rca = engine.analyze(findings, new ServiceCallGraph()).getFirst();

        assertThat(rca.rootCauseService()).isEqualTo("billing-service");
        assertThat(rca.confidence()).isLessThan(0.7);
    }

    @Test
    void findingsFarApartFormSeparateIncidentsAndLowOnlyClustersAreIgnored() {
        List<Finding> findings = List.of(
                finding("inventory-service", DATABASE, Severity.HIGH, 0, null),
                finding("order-service", EXCEPTION, Severity.LOW, Duration.ofMinutes(20).toMillis(), null),
                finding("payment-service", COMMUNICATION, Severity.HIGH, Duration.ofMinutes(60).toMillis(), null));

        List<RcaResult> results = engine.analyze(findings, new ServiceCallGraph());

        assertThat(results).extracting(RcaResult::rootCauseService)
                .containsExactly("inventory-service", "payment-service");
        assertThat(IncidentClusterer.cluster(findings, Duration.ofMinutes(5))).hasSize(3);
    }

    @Test
    void dominantCategoryPrefersStrongestRootCauseSignal() {
        assertThat(RcaEngine.dominantCategory(List.of(
                finding("x-service", TIMEOUT, Severity.HIGH, 0, null),
                finding("x-service", DATABASE, Severity.HIGH, 10, null),
                finding("x-service", EXCEPTION, Severity.HIGH, 20, null)))).isEqualTo(DATABASE);
        assertThat(RcaEngine.dominantCategory(List.of())).isEqualTo(FailureCategory.EXCEPTION);
    }

    @Test
    void causalChainFollowsLongestFailingCallerPath() {
        ServiceCallGraph graph = cascadeGraph();
        graph.addCall("order-service", "audit-service", null, false);
        assertThat(RcaEngine.causalChain("inventory-service",
                Set.of("api-gateway", "order-service", "inventory-service"), graph))
                .containsExactly("api-gateway", "order-service", "inventory-service");
        assertThat(RcaEngine.causalChain("inventory-service", Set.of("inventory-service"), graph))
                .containsExactly("inventory-service");
    }
}