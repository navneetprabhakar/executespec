package com.navneet.executespec.report.model;

import java.util.List;

/**
 * Deterministic, bounded inspection of repository workflow/configuration files.
 *
 * <p>This record contains parser evidence only. It does not claim that an arbitrary
 * natural-language deployment requirement is semantically satisfied; that remains advisory model
 * assessment until a dedicated deterministic rule exists.
 */
public record WorkflowConfigEvidence(Status status, List<FileEvidence> files, String reason) {

  public enum Status {
    PASSED,
    FAILED,
    NOT_APPLICABLE,
    UNAVAILABLE
  }

  public record FileEvidence(
      String path, String contentSha256, ParseStatus parseStatus, String diagnostic) {}

  public enum ParseStatus {
    VALID,
    INVALID
  }

  public WorkflowConfigEvidence {
    files = files == null ? List.of() : List.copyOf(files);
  }

  public static WorkflowConfigEvidence notApplicable() {
    return new WorkflowConfigEvidence(
        Status.NOT_APPLICABLE, List.of(), "No GitHub Actions workflow files were present");
  }

  public static WorkflowConfigEvidence unavailable(String reason) {
    return new WorkflowConfigEvidence(Status.UNAVAILABLE, List.of(), reason);
  }
}
