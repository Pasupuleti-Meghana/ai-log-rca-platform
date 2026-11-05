package com.logsense.ai;

import com.logsense.config.LogSenseProperties;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.Severity;
import com.logsense.processing.masking.PiiMasker;
import com.logsense.processing.flow.ServiceCallGraph;
import com.logsense.rca.RcaEngine;
import com.logsense.rca.RcaResult;
import com.logsense.rca.RecommendationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.logsense.domain.model.FailureCategory.DATABASE;
import static com.logsense.domain.model.FailureCategory.TIMEOUT;
import static com.logsense.support.TestEvents.finding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SummarizerTest {

    private final TemplateSummarizer template = new TemplateSummarizer();
    private final PiiMasker masker = new PiiMasker();
    private RcaResult rca;

    @BeforeEach
    void setUp() {
        Finding db = finding("inventory-service", DATABASE, Severity.HIGH, 900, null).toBuilder()
                .message("HikariPool-1 - Connection pool exhausted for user ops@shop.example")
                .evidence(List.of("HikariPool-1 - Connection pool exhausted")).build();
        List<Finding> findings = List.of(db,
                finding("order-service", TIMEOUT, Severity.HIGH, 5200, "inventory-service"),
                finding("api-gateway", TIMEOUT, Severity.HIGH, 5260, "order-service"));
        rca = new RcaEngine(new RecommendationEngine(), template, Duration.ofMinutes(5))
                .analyze(findings, new ServiceCallGraph()).getFirst();
    }

    @Test
    void templateSummaryIsDeterministicAndExplainsPropagation() {
        String summary = template.summarize(rca);
        assertThat(summary)
                .startsWith("inventory-service is the most likely root cause (")
                .contains("database failure")
                .contains("HikariPool-1 - Connection pool exhausted")
                .contains("propagated upstream inventory-service -> order-service -> api-gateway")
                .contains("surfacing as timeout")
                .contains("Recommended first action:");
        assertThat(template.summarize(rca)).isEqualTo(summary);
        assertThat(template.isAiBacked()).isFalse();
    }

    @Test
    void promptContainsOnlyCondensedMaskedFindings() {
        String prompt = PromptBuilder.userPrompt(rca, 2, masker);
        assertThat(prompt).contains("Detected root cause service: inventory-service")
                .contains("[EMAIL]").doesNotContain("ops@shop.example");
        assertThat(prompt.lines().filter(l -> l.startsWith("- ["))).hasSize(2);
    }

    @Test
    void openAiCompatibleSummarizerUsesChatCompletionsAndMasksAnswer() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://llm.local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://llm.local/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"model\":\"llama3.1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ops@shop.example"))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":" Pool exhausted in inventory-service; contact dba@shop.example "}}]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleSummarizer ai = new OpenAiCompatibleSummarizer(builder.build(), aiConfig(), template, masker);

        assertThat(ai.summarize(rca)).isEqualTo("Pool exhausted in inventory-service; contact [EMAIL]");
        assertThat(ai.isAiBacked()).isTrue();
        server.verify();
    }

    @Test
    void openAiCompatibleSummarizerFallsBackToTemplateOnError() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://llm.local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://llm.local/v1/chat/completions")).andRespond(withServerError());

        OpenAiCompatibleSummarizer ai = new OpenAiCompatibleSummarizer(builder.build(), aiConfig(), template, masker);

        assertThat(ai.summarize(rca)).startsWith("[AI unavailable - template summary] inventory-service");
    }

    private static LogSenseProperties.Ai aiConfig() {
        return new LogSenseProperties.Ai(true, "http://llm.local", null, null, null, null, 0, 0.2);
    }
}