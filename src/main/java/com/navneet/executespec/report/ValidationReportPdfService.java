package com.navneet.executespec.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.ReportIssueDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationReportPdfService {

    private final TemplateEngine templateEngine;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    public byte[] generatePdf(ValidationReport report) {
        List<ReportIssueDTO> issues = parseIssues(report.getIssuesJson());
        List<String> checksPassed = report.getChecksPassed() != null && !report.getChecksPassed().isBlank()
            ? Arrays.asList(report.getChecksPassed().split(","))
            : List.of();
        String languages = report.getLanguages() != null && !report.getLanguages().isBlank()
            ? report.getLanguages().replace(",", ", ")
            : "N/A";

        Context ctx = new Context();
        ctx.setVariable("runId", report.getRunId());
        ctx.setVariable("generatedAt", report.getCreatedAt() != null ? FMT.format(report.getCreatedAt()) : "N/A");
        ctx.setVariable("filesGenerated", report.getFilesGenerated());
        ctx.setVariable("languages", languages);
        ctx.setVariable("issueCount", issues.size());
        ctx.setVariable("pipelineResult", report.getPipelineResult().name());
        ctx.setVariable("checksPassed", checksPassed);
        ctx.setVariable("issues", issues);
        ctx.setVariable("reportText", stripMarkdownHeaders(report.getReportMarkdown()));

        String html = templateEngine.process("report/validation-report-pdf", ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(os);
            return os.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate validation report PDF for run {}: {}", report.getRunId(), e.getMessage(), e);
            throw new RuntimeException("PDF generation failed", e);
        }
    }

    private List<ReportIssueDTO> parseIssues(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse issues JSON for PDF: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Strip markdown heading markers (## ) for plain-text rendering in PDF.
     */
    private String stripMarkdownHeaders(String md) {
        if (md == null) return "";
        return md.replaceAll("(?m)^#{1,4}\\s+", "")
                 .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")  // bold
                 .replaceAll("\\*([^*]+)\\*", "$1");        // italic
    }
}
