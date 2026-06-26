package com.navneet.executespec.report;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

public record ValidationReportEvidenceEvent(
    String runId,
    String reportId,
    Integer verifiedScore,
    Integer rubricScore,
    PipelineResult pipelineResult,
    Instant createdAt,
    List<VerificationEvidenceDTO> verificationLedger) {

  public ValidationReportEvidenceEvent {
    verificationLedger = verificationLedger == null ? List.of() : List.copyOf(verificationLedger);
  }

  public static ValidationReportEvidenceEvent from(
      ValidationReport report, List<VerificationEvidenceDTO> ledger) {
    return new ValidationReportEvidenceEvent(
        report == null ? null : report.getRunId(),
        report == null ? null : report.getId(),
        report == null ? null : report.getVerifiedScore(),
        report == null ? null : report.getRubricScore(),
        report == null ? null : report.getPipelineResult(),
        report == null ? null : report.getCreatedAt(),
        ledger);
  }
}
