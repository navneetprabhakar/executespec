package com.navneet.executespec.report;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.report.model.ValidationReportDTO;
import com.navneet.executespec.report.model.ValidationReportShareDTO;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.service.ProjectShareLinkService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

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
  private final PipelineRunRepository runRepository;
  private final ProjectShareLinkService shareLinkService;
  private final GeneratedSecretScanner secretScanner;
  private final ObjectMapper objectMapper;

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
            .findEntityByRunId(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No report for this run"));
    assertPublicationSafe(report);

    var run =
        runRepository
            .findById(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    if (run.getProjectId() == null || run.getProjectId().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Public report links require a project so expiry, revocation, and audit can be enforced");
    }

    ProjectShareLink link =
        shareLinkService.createLink(
            run.getProjectId(),
            ProjectShareLink.Surface.VALIDATION_REPORT,
            runId,
            "Validation report",
            Instant.now().plusSeconds(ProjectShareLinkService.DEFAULT_EXPIRY_DAYS * 24L * 3600L),
            userDetails.getUser());
    String token = link.getToken();

    return ResponseEntity.ok(Map.of("shareToken", token, "shareUrl", "/shared/" + token));
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

    assertPublicationSafe(entity);

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
        .map(
            report -> {
              assertPublicationSafe(report);
              return ResponseEntity.ok(report);
            })
        .orElse(ResponseEntity.notFound().build());
  }

  private void assertPublicationSafe(Object report) {
    final String serialized;
    try {
      serialized = objectMapper.writeValueAsString(report);
    } catch (JsonProcessingException invalid) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Report could not be validated for publication");
    }
    if (secretScanner.scan(serialized).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Report contains credential-like material and cannot be published");
    }
  }
}
