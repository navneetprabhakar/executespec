package com.navneet.executespec.context;

import java.util.List;

public record ContextAgentInput(
    String currentContextDoc,
    String gitDiffSummary,
    List<String> newPackagesDetected,
    List<String> newFilesDetected,
    List<String> workerReportSummaries,
    String reviewerSummary,
    String recentDecisions,
    String runId,
    String runGoal,
    String hypothesis,
    List<String> changedFiles,
    List<String> failures,
    List<String> doNotRepeat) {

  /** Compatibility constructor for callers that predate the structured handoff fields. */
  public ContextAgentInput(
      String currentContextDoc,
      String gitDiffSummary,
      List<String> newPackagesDetected,
      List<String> newFilesDetected,
      List<String> workerReportSummaries,
      String reviewerSummary,
      String recentDecisions,
      String runId,
      String runGoal) {
    this(
        currentContextDoc,
        gitDiffSummary,
        newPackagesDetected,
        newFilesDetected,
        workerReportSummaries,
        reviewerSummary,
        recentDecisions,
        runId,
        runGoal,
        "",
        newFilesDetected == null ? List.of() : newFilesDetected,
        workerReportSummaries == null ? List.of() : workerReportSummaries,
        recentDecisions == null || recentDecisions.isBlank()
            ? List.of()
            : List.of(recentDecisions));
  }
}
