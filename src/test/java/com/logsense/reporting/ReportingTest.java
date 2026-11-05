package com.logsense.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logsense.domain.entity.IncidentEntity;
import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.JobStatus;
import com.logsense.domain.model.Severity;
import com.logsense.dto.DashboardDto;
import com.logsense.dto.FindingDto;
import com.logsense.dto.IncidentDetailDto;
import com.logsense.dto.TimelineEntryDto;
import com.logsense.mapper.IncidentMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingTest {

    private static final Instant T0 = Instant.parse("2024-10-15T10:00:00Z");

    private static IncidentEntity incident(String root, FailureCategory category, Severity severity, long durationMs,
                                           double confidence, List<String> affected) {
        IncidentEntity e = new IncidentEntity();
        e.setId(UUID.randomUUID());
        e.setJobId(UUID.randomUUID());
        e.setTitle(category + " in " + root);
        e.setRootCauseService(root);
        e.setRootCauseCategory(category);
        e.setSeverity(severity);
        e.setConfidence(confidence);
        e.setDurationMs(durationMs);
        e.setAffectedServices(affected);
        e.setFirstFailureAt(T0);
        e.setCreatedAt(T0.plusSeconds(durationMs));
        return e;
    }

    static IncidentDetailDto detail() {
        UUID id = UUID.randomUUID();
        return new IncidentDetailDto(id, UUID.randomUUID(), "Database failure in inventory-service",
                "inventory-service", FailureCategory.DATABASE, Severity.CRITICAL, 0.95,
                "inventory-service is the most likely root cause", null,
                List.of("api-gateway", "order-service", "inventory-service"),
                List.of("inventory-service", "order-service", "api-gateway"), List.of("7c2a8e4f1d9b3c50"),
                T0, T0.plusSeconds(30), 30000, 2,
                List.of(new TimelineEntryDto(T0, 0, "inventory-service", FailureCategory.DATABASE, Severity.HIGH,
                        "HikariPool-1 - Connection pool exhausted")),
                List.of(new FindingDto(UUID.randomUUID(), FailureCategory.DATABASE, Severity.HIGH, "inventory-service",
                        null, T0, "7c2a8e4f1d9b3c50", 13, null, "HikariPool-1 - Connection pool exhausted",
                        List.of("HikariPool-1 - Connection pool exhausted"), "DatabaseFailureDetector")),
                List.of("Check for connection leaks"), T0);
    }

    @Test
    void dashboardAggregatesByCategoryServiceAndDuration() {
        DashboardService service = new DashboardService(null, null, new IncidentMapper());
        List<IncidentEntity> incidents = List.of(
                incident("inventory-service", FailureCategory.DATABASE, Severity.CRITICAL, 30000, 0.9,
                        List.of("inventory-service", "order-service", "api-gateway")),
                incident("inventory-service", FailureCategory.DATABASE, Severity.HIGH, 10000, 0.8,
                        List.of("inventory-service", "order-service")),
                incident("payment-service", FailureCategory.RETRY_EXHAUSTION, Severity.HIGH, 2000, 0.7,
                        List.of("payment-service", "order-service")));

        DashboardDto dto = service.build(incidents, 4, Map.of(JobStatus.COMPLETED, 3L, JobStatus.FAILED, 1L));

        assertThat(dto.totalIncidents()).isEqualTo(3);
        assertThat(dto.totalJobs()).isEqualTo(4);
        assertThat(dto.incidentsByCategory()).containsEntry(FailureCategory.DATABASE, 2L)
                .containsEntry(FailureCategory.RETRY_EXHAUSTION, 1L);
        assertThat(dto.incidentsBySeverity()).containsEntry(Severity.HIGH, 2L);
        assertThat(dto.incidentsByRootCauseService()).containsEntry("inventory-service", 2L);
        assertThat(dto.topFailingServices().getFirst().service()).isEqualTo("inventory-service");
        assertThat(dto.topFailingServices()).anySatisfy(s -> {
            assertThat(s.service()).isEqualTo("order-service");
            assertThat(s.rootCauseCount()).isZero();
            assertThat(s.affectedCount()).isEqualTo(3);
        });
        assertThat(dto.averageIncidentDurationMs()).isEqualTo(14000.0);
        assertThat(dto.maxIncidentDurationMs()).isEqualTo(30000L);
        assertThat(dto.averageConfidence()).isCloseTo(0.8, org.assertj.core.api.Assertions.within(0.01));
        assertThat(dto.recentIncidents()).hasSize(3);
    }

    @Test
    void dashboardHandlesNoIncidents() {
        DashboardDto dto = new DashboardService(null, null, new IncidentMapper()).build(List.of(), 0, Map.of());
        assertThat(dto.totalIncidents()).isZero();
        assertThat(dto.topFailingServices()).isEmpty();
    }

    @Test
    void pdfReportIsValidPdf() {
        byte[] pdf = new PdfReportGenerator().generate(detail());
        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }

    @Test
    void jsonExportWrapsIncidentInVersionedEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        byte[] json = new JsonReportExporter(mapper).export(detail());
        JsonNode node = mapper.readTree(json);
        assertThat(node.toString()).contains("inventory-service").contains("Check for connection leaks");
        assertThat(node.findValue("rootCauseService").asText()).isEqualTo("inventory-service");
    }
}