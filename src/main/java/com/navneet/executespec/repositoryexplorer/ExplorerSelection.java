package com.navneet.executespec.repositoryexplorer;

import java.util.List;

/**
 * Structured explorer output accepted by the bundle assembler. There is deliberately no field for
 * an exploration transcript or free-form chain of thought.
 */
public record ExplorerSelection(
    Status status,
    ExplorerExperimentArm arm,
    List<String> selectedEvidenceIds,
    List<String> rejectedCandidateSummaries,
    List<String> searchStrategy,
    List<String> enginesConsulted,
    String failureReason) {

  public enum Status {
    NOT_INVOKED,
    VERIFIED,
    FAILED,
    TIMED_OUT,
    BUDGET_EXHAUSTED,
    EMPTY
  }

  public ExplorerSelection {
    if (status == null) throw new IllegalArgumentException("status is required");
    if (arm == null) throw new IllegalArgumentException("arm is required");
    selectedEvidenceIds =
        List.copyOf(selectedEvidenceIds == null ? List.of() : selectedEvidenceIds);
    rejectedCandidateSummaries =
        List.copyOf(rejectedCandidateSummaries == null ? List.of() : rejectedCandidateSummaries);
    searchStrategy = List.copyOf(searchStrategy == null ? List.of() : searchStrategy);
    enginesConsulted = List.copyOf(enginesConsulted == null ? List.of() : enginesConsulted);
    failureReason = normalize(failureReason);
    if (status == Status.VERIFIED && selectedEvidenceIds.isEmpty()) {
      throw new IllegalArgumentException("VERIFIED selection requires evidence IDs");
    }
    if (status != Status.VERIFIED && !selectedEvidenceIds.isEmpty()) {
      throw new IllegalArgumentException("failed or inactive selections cannot carry evidence IDs");
    }
  }

  public static ExplorerSelection notInvoked(ExplorerExperimentArm arm) {
    return new ExplorerSelection(
        Status.NOT_INVOKED, arm, List.of(), List.of(), List.of(), List.of(), null);
  }

  private static String normalize(String value) {
    if (value == null) return null;
    String cleaned = value.strip().replaceAll("\\s+", " ");
    return cleaned.isEmpty() ? null : cleaned;
  }
}
