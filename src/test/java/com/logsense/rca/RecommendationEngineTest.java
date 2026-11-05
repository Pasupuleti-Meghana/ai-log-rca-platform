package com.logsense.rca;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.logsense.support.TestEvents.finding;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    private static Finding withMessage(FailureCategory category, String message) {
        return finding("inventory-service", category, Severity.HIGH, 0, null).toBuilder().message(message)
                .evidence(List.of(message)).build();
    }

    @Test
    void poolExhaustionGetsSpecificHikariAdviceFirst() {
        Finding f = withMessage(FailureCategory.DATABASE, "HikariPool-1 - Connection pool exhausted");
        List<String> recs = engine.recommend(FailureCategory.DATABASE, List.of(f), List.of(f));
        assertThat(recs.getFirst()).containsIgnoringCase("connection leaks");
        assertThat(recs).anyMatch(r -> r.contains("maximum-pool-size"));
        assertThat(recs).anyMatch(r -> r.contains("slow"));
    }

    @Test
    void deadlockGetsLockOrderingAdviceNotPoolAdvice() {
        Finding f = withMessage(FailureCategory.DATABASE, "ERROR: deadlock detected");
        List<String> recs = engine.recommend(FailureCategory.DATABASE, List.of(f), List.of(f));
        assertThat(recs.getFirst()).contains("consistent order");
        assertThat(recs).noneMatch(r -> r.contains("maximum-pool-size"));
    }

    @Test
    void symptomCategoriesAddOneGenericRecommendationEachAndResultIsCapped() {
        Finding root = withMessage(FailureCategory.RETRY_EXHAUSTION, "Max retries exceeded");
        Finding symptom = finding("order-service", FailureCategory.TIMEOUT, Severity.HIGH, 10, null);
        List<String> recs = engine.recommend(FailureCategory.RETRY_EXHAUSTION, List.of(root), List.of(root, symptom));
        assertThat(recs.getFirst()).startsWith("Retries are masking");
        assertThat(recs).anyMatch(r -> r.startsWith("Align timeout budgets"));
        assertThat(recs).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(RecommendationEngine.MAX_RECOMMENDATIONS);
    }
}