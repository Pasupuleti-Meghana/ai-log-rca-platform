package com.logsense.dto;

import java.time.Instant;

/** JSON export envelope. */
public record IncidentReportDto(String reportVersion, Instant generatedAt, String generator, IncidentDetailDto incident) {
}
