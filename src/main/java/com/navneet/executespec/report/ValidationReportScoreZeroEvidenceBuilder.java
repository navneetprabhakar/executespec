package com.navneet.executespec.report;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

/** Reconstructs score-zero ledger evidence only from the persisted V366 causal envelope. */
final class ValidationReportScoreZeroEvidenceBuilder {

  private final ObjectMapper objectMapper;

  ValidationReportScoreZeroEvidenceBuilder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  VerificationEvidenceDTO build(ValidationReport entity, Integer effectiveVerifiedScore) {
    if (effectiveVerifiedScore == null || effectiveVerifiedScore != 0) return null;
    ValidationReportScoreZeroCause.Evidence persisted = parse(entity.getScoreZeroCauseJson());
    if (persisted == null) {
      return evidence(
          "UNKNOWN",
          "INTERNAL_FORMULA",
          "LOW",
          "A zero headline must be bound to an explicit deterministic scoring gate.",
          "Deterministic cause unavailable for this historical or unattributed zero; no cause was"
              + " inferred from LLM or unbound evidence.",
          "validation_report.verified_score");
    }
    List<String> reasons =
        persisted.causes().stream().map(ValidationReportScoreZeroCause.Cause::reason).toList();
    List<String> artifacts =
        persisted.causes().stream().map(ValidationReportScoreZeroCause.Cause::artifact).toList();
    return evidence(
        "FAIL",
        "DETERMINISTIC",
        "HIGH",
        "The zero headline is caused by an explicit deterministic scoring gate.",
        "verifiedScore=0 because " + String.join("; ", reasons) + ".",
        String.join("; ", artifacts));
  }

  private ValidationReportScoreZeroCause.Evidence parse(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      ValidationReportScoreZeroCause.Evidence evidence =
          objectMapper.readValue(json, ValidationReportScoreZeroCause.Evidence.class);
      if (evidence.schemaVersion() != ValidationReportScoreZeroCause.SCHEMA_VERSION
          || !("ADDITIVE_V2".equals(evidence.formula())
              || "MULTIPLICATIVE_LEGACY".equals(evidence.formula()))
          || evidence.causes().isEmpty()
          || evidence.causes().size() > 4) {
        return null;
      }
      for (ValidationReportScoreZeroCause.Cause cause : evidence.causes()) {
        if (!valid(cause)) return null;
      }
      return evidence;
    } catch (Exception malformed) {
      return null;
    }
  }

  private static boolean valid(ValidationReportScoreZeroCause.Cause cause) {
    return cause != null
        && ("CODE_DELIVERED".equals(cause.gate())
            || "BUILD".equals(cause.gate())
            || "BOOT_BUILD".equals(cause.gate())
            || "TESTS".equals(cause.gate()))
        && cause.artifact() != null
        && !cause.artifact().isBlank()
        && cause.reason() != null
        && !cause.reason().isBlank();
  }

  private static VerificationEvidenceDTO evidence(
      String status,
      String source,
      String confidence,
      String claim,
      String summary,
      String artifact) {
    return new VerificationEvidenceDTO(
        "score-zero-cause",
        "Score-zero cause",
        "SCORE",
        status,
        source,
        confidence,
        null,
        claim,
        summary,
        artifact,
        0,
        100);
  }

  static VerificationEvidenceDTO bindWorkspaceGeneration(
      VerificationEvidenceDTO evidence, String workspaceGeneration) {
    return new VerificationEvidenceDTO(
        evidence.id(),
        evidence.label(),
        evidence.category(),
        evidence.status(),
        evidence.source(),
        evidence.confidence(),
        workspaceGeneration,
        evidence.claim(),
        evidence.summary(),
        evidence.artifact(),
        evidence.observed(),
        evidence.total());
  }
}
