package com.navneet.executespec.workspacegeneration;

import java.util.List;

import com.navneet.executespec.state.entity.PipelineRun;

/** Initial workspace proof grants retry admission only, never accepted-generation authority. */
final class WorkspaceGenerationInitialRetryProof {
  private WorkspaceGenerationInitialRetryProof() {}

  static void require(
      PipelineRun run, String generation, List<WorkspaceGenerationAttempt> history) {
    var latest =
        history.stream().findFirst().orElseThrow(WorkspaceGenerationInitialRetryProof::unproven);
    if (!com.navneet.executespec.state.RunMode.BUILD.equalsIgnoreCase(run.getRunMode())
        || run.getAcceptedGenerationAttemptId() != null
        || !Integer.valueOf(-1).equals(run.getAcceptedTaskIndex())
        || generation == null
        || !generation.equals(run.getAcceptedWorkspaceGeneration())
        || !run.getRunId().equals(latest.getRunId())
        || !Integer.valueOf(0).equals(latest.getTaskIndex())
        || latest.getAttemptKind() != WorkspaceGenerationKind.BUILD_TASK
        || latest.getState() != WorkspaceGenerationState.ROLLED_BACK
        || !generation.equals(latest.getBeforeGeneration())
        || latest.getResolvedAt() == null
        || latest.getBeforeCheckpointCompletedAt() == null
        || latest.getBeforeCheckpointKey() == null
        || latest.getBeforeCheckpointDigest() == null
        || !"COMPLETED".equals(latest.getTaskTransitionState())
        || !"COMPLETED".equals(latest.getOperationTransitionState())) throw unproven();
  }

  private static IllegalStateException unproven() {
    return new IllegalStateException("WORKSPACE_GENERATION_INITIAL_RETRY_UNPROVEN");
  }
}
