package com.logsense.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logsense.tools.LogGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end: upload -> async parse/detect/RCA (real executors, H2) -> REST queries, PDF and JSON reports. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogSenseIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterRegistry meterRegistry;

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode getJson(String url, Object... vars) throws Exception {
        return json(mvc.perform(get(url, vars)).andExpect(status().isOk()).andReturn());
    }

    private String upload(String fileName, byte[] bytes) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("file", fileName, "text/plain", bytes)))
                .andExpect(status().isAccepted())
                .andReturn();
        return json(result).get("id").asText();
    }

    private JsonNode awaitCompletion(String jobId) {
        AtomicReference<JsonNode> job = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            JsonNode node = getJson("/api/v1/jobs/{id}", jobId);
            job.set(node);
            assertThat(node.get("status").asText()).isIn("COMPLETED", "FAILED");
        });
        assertThat(job.get().get("status").asText())
                .as("job error: %s", job.get().path("errorMessage").asText())
                .isEqualTo("COMPLETED");
        assertThat(job.get().get("progress").asInt()).isEqualTo(100);
        return job.get();
    }

    private String analyseSample(String name) throws Exception {
        String jobId = upload(name, Files.readAllBytes(Path.of("samples", name)));
        awaitCompletion(jobId);
        return jobId;
    }

    @Test
    void cascadeSampleIdentifiesInventoryDatabaseAsRootCause() throws Exception {
        String jobId = analyseSample("cascade-db-pool-exhaustion.log");

        JsonNode job = getJson("/api/v1/jobs/{id}", jobId);
        assertThat(job.get("detectedFormat").asText()).isEqualTo("SPRING_BOOT");
        assertThat(job.get("unparsedLines").asLong()).isZero();
        assertThat(job.get("incidentsCount").asInt()).isEqualTo(1);

        JsonNode incidents = getJson("/api/v1/jobs/{id}/incidents", jobId);
        assertThat(incidents).hasSize(1);
        JsonNode incident = incidents.get(0);
        assertThat(incident.get("rootCauseService").asText()).isEqualTo("inventory-service");
        assertThat(incident.get("rootCauseCategory").asText()).isEqualTo("DATABASE");
        assertThat(incident.get("confidence").asDouble()).isGreaterThanOrEqualTo(0.8);

        String incidentId = incident.get("id").asText();
        JsonNode detail = getJson("/api/v1/incidents/{id}", incidentId);
        assertThat(detail.get("causalChain")).extracting(JsonNode::asText)
                .containsExactly("api-gateway", "order-service", "inventory-service");
        assertThat(detail.get("summary").asText()).startsWith("inventory-service is the most likely root cause");
        assertThat(detail.get("recommendations").get(0).asText()).containsIgnoringCase("connection leaks");
        assertThat(detail.get("timeline").get(0).get("offsetMs").asLong()).isZero();
        assertThat(detail.get("traceIds")).extracting(JsonNode::asText).contains("7c2a8e4f1d9b3c50");

        byte[] pdf = mvc.perform(get("/api/v1/incidents/{id}/report/pdf", incidentId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        JsonNode export = json(mvc.perform(get("/api/v1/incidents/{id}/report/json", incidentId))
                .andExpect(status().isOk()).andReturn());
        assertThat(export.findValue("rootCauseService").asText()).isEqualTo("inventory-service");
        assertThat(export.findValue("findings")).isNotEmpty();

        JsonNode graph = getJson("/api/v1/jobs/{id}/service-graph", jobId);
        List<String> edges = new ArrayList<>();
        graph.forEach(e -> edges.add(e.get("caller").asText() + "->" + e.get("callee").asText()));
        assertThat(edges).contains("api-gateway->order-service", "order-service->inventory-service");

        JsonNode flow = getJson("/api/v1/jobs/{id}/traces/{traceId}", jobId, "7c2a8e4f1d9b3c50");
        assertThat(flow.get("failed").asBoolean()).isTrue();
        assertThat(flow.get("services")).extracting(JsonNode::asText)
                .containsExactly("api-gateway", "order-service", "inventory-service");

        JsonNode search = getJson("/api/v1/incidents?service=inventory-service&severity=CRITICAL&jobId=" + jobId);
        assertThat(search.get("totalElements").asLong()).isEqualTo(1);
        JsonNode none = getJson("/api/v1/incidents?service=shipping-service&jobId=" + jobId);
        assertThat(none.get("totalElements").asLong()).isZero();

        JsonNode dashboard = getJson("/api/v1/dashboard");
        assertThat(dashboard.get("totalIncidents").asLong()).isPositive();
        assertThat(dashboard.get("incidentsByCategory").get("DATABASE").asLong()).isPositive();

        assertThat(meterRegistry.get("logsense.logs.parsed").counter().count()).isPositive();
        assertThat(meterRegistry.get("logsense.incidents.detected").tag("category", "DATABASE").counter().count())
                .isPositive();
        assertThat(meterRegistry.get("logsense.job.processing.time").timer().count()).isPositive();
    }

    @Test
    void retrySampleBlamesPaymentServiceAndMasksPii() throws Exception {
        String jobId = analyseSample("retry-exhaustion.log");

        JsonNode incident = getJson("/api/v1/jobs/{id}/incidents", jobId).get(0);
        assertThat(incident.get("rootCauseService").asText()).isEqualTo("payment-service");
        assertThat(incident.get("rootCauseCategory").asText()).isEqualTo("RETRY_EXHAUSTION");

        JsonNode detail = getJson("/api/v1/incidents/{id}", incident.get("id").asText());
        assertThat(detail.get("summary").asText()).contains("fraud-check-service");
        assertThat(detail.toString()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");

        String flow = getJson("/api/v1/jobs/{id}/traces/{traceId}", jobId, "ab12cd34ef56ab78").toString();
        assertThat(flow).contains("[EMAIL]").contains("[CARD]")
                .doesNotContain("john.doe@example.com").doesNotContain("4111 1111 1111 1111");
    }

    @Test
    void jsonSampleBlamesOrderServiceDeadlock() throws Exception {
        String jobId = analyseSample("json-format.log");
        assertThat(getJson("/api/v1/jobs/{id}", jobId).get("detectedFormat").asText()).isEqualTo("JSON");
        JsonNode incident = getJson("/api/v1/jobs/{id}/incidents", jobId).get(0);
        assertThat(incident.get("rootCauseService").asText()).isEqualTo("order-service");
        assertThat(incident.get("rootCauseCategory").asText()).isEqualTo("DATABASE");
    }

    @Test
    void mixedFormatSampleIsParsedAndAnalysed() throws Exception {
        String jobId = analyseSample("mixed-format.log");
        JsonNode job = getJson("/api/v1/jobs/{id}", jobId);
        assertThat(job.get("detectedFormat").asText()).isEqualTo("MIXED");
        assertThat(job.get("unparsedLines").asLong()).isZero();
        assertThat(job.get("incidentsCount").asInt()).isPositive();
    }

    @Test
    void textEndpointAndGeneratedLargeFileAreProcessed() throws Exception {
        MvcResult accepted = mvc.perform(post("/api/v1/jobs/text").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceName":"billing-service.log","content":"2024-10-15 10:00:00,000 [main] ERROR com.acme.Billing - java.sql.SQLException: Connection closed"}
                                """))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode textJob = awaitCompletion(json(accepted).get("id").asText());
        assertThat(textJob.get("findingsCount").asInt()).isEqualTo(1);

        StringWriter writer = new StringWriter();
        LogGenerator.generate(writer, 20_000, 11L);
        JsonNode big = awaitCompletion(upload("generated.log", writer.toString().getBytes(StandardCharsets.UTF_8)));
        assertThat(big.get("totalLines").asLong()).isEqualTo(20_000);
        assertThat(big.get("parsedEvents").asLong()).isEqualTo(20_000);
        assertThat(big.get("traceCount").asInt()).isGreaterThan(1000);
        assertThat(big.get("incidentsCount").asInt()).isPositive();
    }

    @Test
    void unknownResourcesReturnProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/incidents/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", "evil.exe", "application/octet-stream",
                        new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest());
    }
}