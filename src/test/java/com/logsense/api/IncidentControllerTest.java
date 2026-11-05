package com.logsense.api;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;
import com.logsense.dto.DashboardDto;
import com.logsense.dto.IncidentSearchCriteria;
import com.logsense.dto.PageResponse;
import com.logsense.exception.ResourceNotFoundException;
import com.logsense.reporting.DashboardService;
import com.logsense.service.IncidentService;
import com.logsense.service.ReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({IncidentController.class, DashboardController.class})
class IncidentControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockBean
    private IncidentService incidentService;
    @MockBean
    private ReportService reportService;
    @MockBean
    private DashboardService dashboardService;

    @Test
    void searchPassesFiltersPagingAndSorting() throws Exception {
        when(incidentService.search(any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 10, 0, 0, "UNSORTED"));

        mvc.perform(get("/api/v1/incidents")
                        .param("service", "inventory-service").param("severity", "HIGH")
                        .param("category", "DATABASE")
                        .param("from", "2024-10-15T00:00:00Z").param("to", "2024-10-16T00:00:00Z")
                        .param("page", "0").param("size", "10").param("sort", "confidence,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        ArgumentCaptor<IncidentSearchCriteria> criteria = ArgumentCaptor.forClass(IncidentSearchCriteria.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentService).search(criteria.capture(), pageable.capture());
        assertThat(criteria.getValue().service()).isEqualTo("inventory-service");
        assertThat(criteria.getValue().severity()).isEqualTo(Severity.HIGH);
        assertThat(criteria.getValue().category()).isEqualTo(FailureCategory.DATABASE);
        assertThat(criteria.getValue().from()).isEqualTo(Instant.parse("2024-10-15T00:00:00Z"));
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort().getOrderFor("confidence").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void invalidSeverityIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/incidents").param("severity", "APOCALYPTIC"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unsupportedSortPropertyIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/incidents").param("sort", "password,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Unsupported sort property")));
    }

    @Test
    void invertedDateRangeIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/incidents").param("from", "2024-10-16T00:00:00Z").param("to", "2024-10-15T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativePageIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/incidents").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void missingIncidentReturns404ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.get(id)).thenThrow(new ResourceNotFoundException("Incident", id));
        mvc.perform(get("/api/v1/incidents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://logsense.dev/problems/not-found"));
    }

    @Test
    void pdfReportIsServedAsAttachment() throws Exception {
        UUID id = UUID.randomUUID();
        when(reportService.pdf(id)).thenReturn("%PDF-1.4 fake".getBytes());
        mvc.perform(get("/api/v1/incidents/{id}/report/pdf", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")));
    }

    @Test
    void dashboardReturnsAggregates() throws Exception {
        when(dashboardService.dashboard(any(), any())).thenReturn(new DashboardDto(1, Map.of(), 2,
                Map.of(FailureCategory.DATABASE, 2L), Map.of(), Map.of("inventory-service", 2L), List.of(),
                1000.0, 2000L, 0.9, List.of()));
        mvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncidents").value(2))
                .andExpect(jsonPath("$.incidentsByCategory.DATABASE").value(2));
    }
}