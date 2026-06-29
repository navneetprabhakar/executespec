package com.navneet.executespec.report;

import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.report.model.ValidationReportDTO;
import com.navneet.executespec.report.model.ValidationReportShareDTO;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ValidationReportController {

  private final ValidationReportService reportService;
  private final ValidationReportPdfService pdfService;
  private final RunAccessGuard runAccessGuard;

  @GetMapping("/runs/{runId}/report")
  public ResponseEntity<ValidationReportDTO> getReport(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {

    runAccessGuard.requireRunAccess(runId, userDetails);

    return reportService
        .getByRunId(runId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/runs/{runId}/report/share")
  public ResponseEntity<Map<String, String>> shareReport(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {

    runAccessGuard.requireRunWriteAccess(runId, userDetails);

    var report =
        reportService
            .getByRunId(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No report for this run"));

    String token = reportService.share(runId);

    return ResponseEntity.ok(Map.of("shareToken", token, "shareUrl", "/reports/shared/" + token));
  }

  @GetMapping("/runs/{runId}/report/pdf")
  public ResponseEntity<byte[]> downloadPdf(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {

    runAccessGuard.requireRunAccess(runId, userDetails);

    var entity =
        reportService
            .findEntityByRunId(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No report for this run"));

    byte[] pdf = pdfService.generatePdf(entity);

    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"validation-report-" + runId.substring(0, 8) + ".pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }

  @GetMapping("/reports/shared/{shareToken}")
  public ResponseEntity<ValidationReportShareDTO> getSharedReport(@PathVariable String shareToken) {

    return reportService
        .getByShareToken(shareToken)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
