package com.logsense.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logsense.dto.IncidentDetailDto;
import com.logsense.dto.IncidentReportDto;
import com.logsense.exception.ReportGenerationException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Pretty-printed JSON export suitable for ticketing systems / post-mortem tooling. */
@Component
public class JsonReportExporter {

    public static final String REPORT_VERSION = "1.0";

    private final ObjectMapper objectMapper;

    public JsonReportExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] export(IncidentDetailDto incident) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(new IncidentReportDto(REPORT_VERSION, Instant.now(), "LogSense", incident));
        } catch (JsonProcessingException e) {
            throw new ReportGenerationException("Could not export incident " + incident.id(), e);
        }
    }
}
