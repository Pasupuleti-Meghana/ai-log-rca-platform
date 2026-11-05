package com.logsense.api;

import com.logsense.dto.AnalyzeTextRequest;
import com.logsense.dto.IncidentSummaryDto;
import com.logsense.dto.JobResponse;
import com.logsense.dto.PageResponse;
import com.logsense.dto.ServiceEdgeDto;
import com.logsense.processing.flow.TransactionFlow;
import com.logsense.service.IncidentService;
import com.logsense.service.JobService;
import com.logsense.service.TransactionFlowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Analysis jobs", description = "Upload logs and track asynchronous analysis")
public class JobController {

    private final JobService jobService;
    private final IncidentService incidentService;
    private final TransactionFlowService flowService;

    public JobController(JobService jobService, IncidentService incidentService, TransactionFlowService flowService) {
        this.jobService = jobService;
        this.incidentService = incidentService;
        this.flowService = flowService;
    }

    @Operation(summary = "Upload a log file (up to 500 MB) and start asynchronous analysis")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobResponse> upload(@RequestPart("file") MultipartFile file) {
        return accepted(jobService.submit(file));
    }

    @Operation(summary = "Analyse raw log text (small snippets)")
    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobResponse> analyzeText(@Valid @RequestBody AnalyzeTextRequest request) {
        return accepted(jobService.submitText(request));
    }

    @Operation(summary = "Get job status and progress")
    @GetMapping("/{jobId}")
    public JobResponse get(@PathVariable UUID jobId) {
        return jobService.getJob(jobId);
    }

    @Operation(summary = "List jobs (newest first)")
    @GetMapping
    public PageResponse<JobResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return jobService.listJobs(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Operation(summary = "Incidents detected by a job")
    @GetMapping("/{jobId}/incidents")
    public List<IncidentSummaryDto> incidents(@PathVariable UUID jobId) {
        return incidentService.byJob(jobId);
    }

    @Operation(summary = "Service call graph (edges with call count, error count, latency)")
    @GetMapping("/{jobId}/service-graph")
    public List<ServiceEdgeDto> serviceGraph(@PathVariable UUID jobId) {
        return jobService.serviceGraph(jobId);
    }

    @Operation(summary = "Reconstructed transaction flow for a trace id")
    @GetMapping("/{jobId}/traces/{traceId}")
    public TransactionFlow trace(@PathVariable UUID jobId, @PathVariable @Size(max = 64) String traceId) {
        return flowService.flow(jobId, traceId);
    }

    private static ResponseEntity<JobResponse> accepted(JobResponse job) {
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/jobs/{id}").buildAndExpand(job.id()).toUri();
        return ResponseEntity.accepted().location(location).body(job);
    }
}
