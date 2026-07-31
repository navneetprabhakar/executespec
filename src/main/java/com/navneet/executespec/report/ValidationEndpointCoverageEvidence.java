package com.navneet.executespec.report;

import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

/** Builds the endpoint-applicability row without coupling the main ledger builder to its policy. */
final class ValidationEndpointCoverageEvidence {

  private ValidationEndpointCoverageEvidence() {}

  static VerificationEvidenceDTO build(
      ValidationReport report, Integer coverage, boolean cited, String artifact) {
    boolean applicable =
        report.getRuntimeSurface() == null || "HTTP".equalsIgnoreCase(report.getRuntimeSurface());
    return new VerificationEvidenceDTO(
        "endpoint-coverage",
        "Endpoint coverage",
        "ENDPOINTS",
        status(applicable, coverage, cited),
        applicable ? "LLM_ASSISTED_COUNT" : "NOT_APPLICABLE",
        applicable ? coverage == null ? "LOW" : "MEDIUM" : "HIGH",
        report.getWorkspaceGeneration(),
        applicable
            ? "Expected API endpoints are present in the generated implementation."
            : "Endpoint coverage does not apply to this runtime surface.",
        summary(report, applicable, coverage),
        applicable ? artifact : "validation_report.runtime_surface",
        applicable ? coverage : null,
        applicable && coverage != null ? 100 : null);
  }

  private static String status(boolean applicable, Integer coverage, boolean cited) {
    if (!applicable) return "N/A";
    if (coverage == null || !cited) return "UNKNOWN";
    if (coverage >= 95) return "PASS";
    return coverage >= 70 ? "WARN" : "FAIL";
  }

  private static String summary(ValidationReport report, boolean applicable, Integer coverage) {
    if (!applicable) {
      return "Runtime surface " + report.getRuntimeSurface() + " has no HTTP endpoint contract.";
    }
    return coverage == null
        ? "No endpoint coverage evidence was captured."
        : "Endpoint coverage: " + coverage + "%.";
  }
}
