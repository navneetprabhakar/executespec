package com.navneet.executespec.workspacegeneration;

import com.navneet.executespec.state.entity.PipelineRun;

/** Exact accepted-frontier proof shared by recovery and downstream admission. */
final class WorkspaceGenerationFrontierVerifier {
  private WorkspaceGenerationFrontierVerifier() {}

  static void assertAccepted(
      String runId,
      String currentGeneration,
      PipelineRun run,
      WorkspaceGenerationAttemptRepository attempts,
      WorkspaceGenerationTelemetry telemetry,
      String boundary) {
    if (!com.navneet.executespec.state.RunMode.BUILD.equalsIgnoreCase(run.getRunMode())) return;
    if (run.getAcceptedWorkspaceGeneration() == null
        || !run.getAcceptedWorkspaceGeneration().equals(currentGeneration)
        || run.getAcceptedGenerationAttemptId() == null) {
      telemetry.control(
          runId,
          null,
          run.getAcceptedGenerationAttemptId(),
          "DOWNSTREAM_ADMISSION",
          "DENIED_FRONTIER_MISMATCH",
          boundary);
      throw new IllegalStateException("WORKSPACE_GENERATION_FRONTIER_MISMATCH");
    }
    WorkspaceGenerationAttempt accepted =
        attempts
            .findById(run.getAcceptedGenerationAttemptId())
            .orElseThrow(
                () -> new IllegalStateException("WORKSPACE_GENERATION_ACCEPTANCE_MISSING"));
    WorkspaceGenerationAcceptanceProof.assertExact(runId, currentGeneration, run, accepted);
  }
}
