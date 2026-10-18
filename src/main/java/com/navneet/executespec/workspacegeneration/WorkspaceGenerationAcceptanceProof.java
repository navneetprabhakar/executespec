package com.navneet.executespec.workspacegeneration;

import java.util.Objects;

import com.navneet.executespec.state.entity.PipelineRun;

/** Exact cross-row proof for the one workspace generation accepted by a BUILD run. */
final class WorkspaceGenerationAcceptanceProof {

  private WorkspaceGenerationAcceptanceProof() {}

  static void assertExact(
      String runId,
      String expectedGeneration,
      PipelineRun run,
      WorkspaceGenerationAttempt accepted) {
    if (expectedGeneration == null
        || accepted.getState() != WorkspaceGenerationState.ACCEPTED
        || !runId.equals(accepted.getRunId())
        || !expectedGeneration.equals(accepted.getCandidateGeneration())
        || !Objects.equals(
            run.getAcceptedTaskIndex(),
            accepted.getAttemptKind() == WorkspaceGenerationKind.CORRECTION
                ? accepted.getFrontierTaskIndex()
                : accepted.getTaskIndex())
        || run.getAcceptedCheckpointKind() == null
        || run.getAcceptedCheckpointKey() == null
        || run.getAcceptedCheckpointDigest() == null
        || run.getAcceptedCheckpointCompletedAt() == null
        || !Objects.equals(run.getAcceptedCheckpointKind(), accepted.getCheckpointKind())
        || !Objects.equals(run.getAcceptedCheckpointKey(), accepted.getCheckpointKey())
        || !Objects.equals(run.getAcceptedCheckpointDigest(), accepted.getCheckpointDigest())
        || !Objects.equals(
            run.getAcceptedCheckpointCompletedAt(), accepted.getCheckpointCompletedAt())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ACCEPTANCE_INVALID");
    }
  }
}
