package com.logsense.api;

import com.logsense.dto.DashboardDto;
import com.logsense.exception.InvalidRequestException;
import com.logsense.reporting.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Aggregated RCA statistics")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "Incidents by category/severity/service, top failing services, MTTR-like stats")
    @GetMapping
    public DashboardDto dashboard(@RequestParam(required = false) Instant from,
                                  @RequestParam(required = false) Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
        return dashboardService.dashboard(from, to);
    }
}
