package com.logsense.api;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Severity;
import com.logsense.dto.IncidentDetailDto;
import com.logsense.dto.IncidentSearchCriteria;
import com.logsense.dto.IncidentSummaryDto;
import com.logsense.dto.PageResponse;
import com.logsense.exception.InvalidRequestException;
import com.logsense.service.IncidentService;
import com.logsense.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents", description = "Incident history, RCA details and reports")
public class IncidentController {

    static final Set<String> SORTABLE = Set.of("firstFailureAt", "createdAt", "severity", "confidence",
            "rootCauseService", "durationMs", "findingCount");

    private final IncidentService incidentService;
    private final ReportService reportService;

    public IncidentController(IncidentService incidentService, ReportService reportService) {
        this.incidentService = incidentService;
        this.reportService = reportService;
    }

    @Operation(summary = "Search incident history with filtering, pagination and sorting")
    @GetMapping
    public PageResponse<IncidentSummaryDto> search(
            @RequestParam(required = false) @Size(max = 128) String service,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) FailureCategory category,
            @RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(defaultValue = "firstFailureAt,desc") String sort) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
        return incidentService.search(new IncidentSearchCriteria(service, severity, category, jobId, from, to),
                PageRequest.of(page, size, parseSort(sort)));
    }

    @Operation(summary = "Full RCA details of an incident")
    @GetMapping("/{id}")
    public IncidentDetailDto get(@PathVariable UUID id) {
        return incidentService.get(id);
    }

    @Operation(summary = "Download the RCA report as PDF")
    @GetMapping(value = "/{id}/report/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return download(reportService.pdf(id), MediaType.APPLICATION_PDF, "rca-" + id + ".pdf");
    }

    @Operation(summary = "Export the RCA report as JSON")
    @GetMapping(value = "/{id}/report/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> json(@PathVariable UUID id) {
        return download(reportService.json(id), MediaType.APPLICATION_JSON, "rca-" + id + ".json");
    }

    static Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!SORTABLE.contains(property)) {
            throw new InvalidRequestException("Unsupported sort property '" + property + "'. Allowed: " + SORTABLE);
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length > 1) {
            direction = Sort.Direction.fromOptionalString(parts[1].trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new InvalidRequestException("Sort direction must be 'asc' or 'desc'"));
        }
        return Sort.by(direction, property);
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentLength(body.length)
                .body(body);
    }
}
