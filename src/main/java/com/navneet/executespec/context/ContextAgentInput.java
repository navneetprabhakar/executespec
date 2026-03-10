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
    String runGoal
) {}
