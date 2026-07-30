package com.navneet.executespec.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;

import lombok.extern.slf4j.Slf4j;

/** Maps persisted workflow inspection evidence into one reloadable verification-ledger fact. */
@Slf4j
final class WorkflowVerificationEvidenceMapper {

  private WorkflowVerificationEvidenceMapper() {}

  static VerificationEvidenceDTO from(String json, ObjectMapper objectMapper) {
    WorkflowConfigEvidence evidence = parse(json, objectMapper);
    String status =
        evidence == null
            ? "UNKNOWN"
            : switch (evidence.status()) {
              case PASSED -> "PASS";
              case FAILED -> "FAIL";
              case NOT_APPLICABLE -> "N/A";
              case UNAVAILABLE -> "UNKNOWN";
            };
    String confidence =
        evidence == null || evidence.status() == WorkflowConfigEvidence.Status.UNAVAILABLE
            ? "LOW"
            : "HIGH";
    String summary =
        evidence == null
            ? "No deterministic workflow/config evidence was persisted."
            : evidence.reason()
                + (evidence.files().isEmpty()
                    ? "."
                    : "; "
                        + evidence.files().size()
                        + " workflow file(s) inspected: "
                        + paths(evidence));
    return new VerificationEvidenceDTO(
        "workflow-config",
        "Workflow/config evidence",
        "CONFIG",
        status,
        "DETERMINISTIC_CONFIG_PARSER",
        confidence,
        null,
        "Repository workflow files are syntactically valid and bound to exact source digests.",
        summary,
        "validation_report.workflow_config_evidence_json",
        evidence == null ? null : validCount(evidence),
        evidence == null ? null : evidence.files().size());
  }

  private static WorkflowConfigEvidence parse(String json, ObjectMapper objectMapper) {
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readValue(json, WorkflowConfigEvidence.class);
    } catch (Exception malformed) {
      log.warn("Validation report has malformed workflow/config evidence");
      return WorkflowConfigEvidence.unavailable("Persisted workflow/config evidence is malformed");
    }
  }

  private static int validCount(WorkflowConfigEvidence evidence) {
    return (int)
        evidence.files().stream()
            .filter(file -> file.parseStatus() == WorkflowConfigEvidence.ParseStatus.VALID)
            .count();
  }

  private static String paths(WorkflowConfigEvidence evidence) {
    return evidence.files().stream()
        .map(
            file ->
                file.path()
                    + "@"
                    + (file.contentSha256().isBlank()
                        ? "unhashed"
                        : file.contentSha256().substring(0, 12))
                    + ":"
                    + file.parseStatus())
        .limit(8)
        .collect(java.util.stream.Collectors.joining(", "));
  }
}
