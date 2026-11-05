package com.logsense.service;

import com.logsense.dto.IncidentDetailDto;
import com.logsense.reporting.JsonReportExporter;
import com.logsense.reporting.PdfReportGenerator;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ReportService {

    private final IncidentService incidentService;
    private final PdfReportGenerator pdfGenerator;
    private final JsonReportExporter jsonExporter;

    public ReportService(IncidentService incidentService, PdfReportGenerator pdfGenerator,
                         JsonReportExporter jsonExporter) {
        this.incidentService = incidentService;
        this.pdfGenerator = pdfGenerator;
        this.jsonExporter = jsonExporter;
    }

    public byte[] pdf(UUID incidentId) {
        IncidentDetailDto incident = incidentService.get(incidentId);
        return pdfGenerator.generate(incident);
    }

    public byte[] json(UUID incidentId) {
        return jsonExporter.export(incidentService.get(incidentId));
    }
}
