package com.navneet.executespec.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;

/** Provider-only bounded diagnostics; never part of the public task/goal evidence projection. */
final class BuildAcceptanceDiagnostics {
  private static final ObjectMapper JSON = new ObjectMapper();

  private BuildAcceptanceDiagnostics() {}

  static String from(WorkspaceGenerationLifecycle.Verification verification) {
    if (verification == null) return null;
    String detail = "";
    try {
      var evidence =
          JSON.readTree(verification.evidenceJson() == null ? "{}" : verification.evidenceJson());
      detail = evidence.path("testOutput").asText("");
      if (detail.isBlank()) detail = evidence.path("buildOutput").asText("");
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
      detail = "Detailed verification output is unavailable";
    }
    if (detail.length() > 2_000) detail = detail.substring(detail.length() - 2_000);
    return "Frozen acceptance "
        + verification.outcome()
        + "; failed tests="
        + verification.testsFailed()
        + "; test errors="
        + verification.testsErrors()
        + "; "
        + detail;
  }
}
