package com.logsense.reporting;

import com.logsense.dto.FindingDto;
import com.logsense.dto.IncidentDetailDto;
import com.logsense.dto.TimelineEntryDto;
import com.logsense.exception.ReportGenerationException;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;

/** Renders an incident RCA report as PDF using OpenPDF. */
@Component
public class PdfReportGenerator {

    private static final int MAX_FINDINGS_IN_PDF = 50;
    private static final Color HEADER_BG = new Color(33, 37, 41);
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(13, 71, 161));
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

    public byte[] generate(IncidentDetailDto incident) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 42, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle("LogSense RCA - " + incident.title());
            document.addCreator("LogSense");
            document.open();

            document.add(new Paragraph("LogSense - Root Cause Analysis Report", TITLE));
            document.add(new Paragraph(incident.title(), BOLD));
            document.add(new Paragraph("Generated at " + Instant.now(), SMALL));
            document.add(Chunk.NEWLINE);

            PdfPTable overview = new PdfPTable(new float[]{1.2f, 3f});
            overview.setWidthPercentage(100);
            row(overview, "Incident ID", String.valueOf(incident.id()));
            row(overview, "Root cause service", incident.rootCauseService());
            row(overview, "Root cause category", String.valueOf(incident.rootCauseCategory()));
            row(overview, "Confidence", Math.round(incident.confidence() * 100) + "%");
            row(overview, "Severity", String.valueOf(incident.severity()));
            row(overview, "Window", incident.firstFailureAt() + "  ->  " + incident.lastFailureAt()
                    + "  (" + incident.durationMs() + " ms)");
            row(overview, "Causal chain", String.join("  ->  ", incident.causalChain()));
            row(overview, "Affected services", String.join(", ", incident.affectedServices()));
            row(overview, "Findings", String.valueOf(incident.findingCount()));
            document.add(overview);

            section(document, "Summary");
            document.add(new Paragraph(nullSafe(incident.summary()), BODY));
            if (incident.aiSummary() != null) {
                section(document, "AI-assisted summary");
                document.add(new Paragraph(incident.aiSummary(), BODY));
            }

            section(document, "Recommendations");
            com.lowagie.text.List recs = new com.lowagie.text.List(true, 12);
            incident.recommendations().forEach(r -> recs.add(new com.lowagie.text.ListItem(r, BODY)));
            document.add(recs);

            section(document, "Incident timeline");
            PdfPTable timeline = table(new float[]{1.1f, 1.4f, 1.3f, 0.9f, 4f},
                    "Offset (ms)", "Service", "Category", "Severity", "Description");
            for (TimelineEntryDto t : incident.timeline()) {
                cells(timeline, "+" + t.offsetMs(), t.service(), String.valueOf(t.category()),
                        String.valueOf(t.severity()), t.description());
            }
            document.add(timeline);

            section(document, "Findings (top " + MAX_FINDINGS_IN_PDF + ")");
            PdfPTable findings = table(new float[]{1.6f, 1.3f, 1.2f, 0.9f, 4f},
                    "Timestamp", "Service", "Category", "Severity", "Message");
            List<FindingDto> top = incident.findings().stream().limit(MAX_FINDINGS_IN_PDF).toList();
            for (FindingDto f : top) {
                cells(findings, String.valueOf(f.timestamp()), f.service(), String.valueOf(f.category()),
                        String.valueOf(f.severity()), f.message());
            }
            document.add(findings);

            if (!incident.traceIds().isEmpty()) {
                section(document, "Related trace IDs");
                document.add(new Paragraph(String.join(", ", incident.traceIds()), SMALL));
            }
        } catch (DocumentException e) {
            throw new ReportGenerationException("Could not render PDF for incident " + incident.id(), e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private static void section(Document document, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, H2);
        p.setSpacingBefore(12);
        p.setSpacingAfter(4);
        document.add(p);
    }

    private static void row(PdfPTable table, String key, String value) {
        PdfPCell k = new PdfPCell(new Phrase(key, BOLD));
        k.setBackgroundColor(new Color(236, 239, 241));
        table.addCell(k);
        table.addCell(new PdfPCell(new Phrase(nullSafe(value), BODY)));
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, HEADER));
            cell.setBackgroundColor(HEADER_BG);
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.addCell(cell);
        }
        return table;
    }

    private static void cells(PdfPTable table, String... values) {
        for (String v : values) {
            table.addCell(new PdfPCell(new Phrase(nullSafe(v), SMALL)));
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "-" : s;
    }
}
