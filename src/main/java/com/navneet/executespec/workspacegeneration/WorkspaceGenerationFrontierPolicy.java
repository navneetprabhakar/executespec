package com.navneet.executespec.workspacegeneration;

import java.util.Objects;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunMutationRepository;

/** Distinguishes append-only initial execution from exact accepted-prefix replacement. */
final class WorkspaceGenerationFrontierPolicy {

  private WorkspaceGenerationFrontierPolicy() {}

  static void assertCorrection(WorkspaceGenerationAttempt attempt, String before, PipelineRun run) {
    if (!Objects.equals(attempt.getBaseFrontierAttemptId(), run.getAcceptedGenerationAttemptId())
        || !Objects.equals(attempt.getFrontierTaskIndex(), run.getAcceptedTaskIndex())
        || !Objects.equals(attempt.getBaseFrontierGeneration(), before)
        || !Objects.equals(before, run.getAcceptedWorkspaceGeneration())) {
      throw new IllegalStateException("WORKSPACE_CORRECTION_FRONTIER_MISMATCH");
    }
  }

  static int advance(
      PipelineRunMutationRepository mutations, WorkspaceGenerationAttempt attempt, String current) {
    if (attempt.getAttemptKind() == WorkspaceGenerationKind.CORRECTION) {
      return mutations.replaceAcceptedWorkspaceFrontier(
          attempt.getRunId(),
          attempt.getBeforeGeneration(),
          attempt.getBaseFrontierAttemptId(),
          attempt.getFrontierTaskIndex(),
          current,
          attempt.getId(),
          attempt.getCheckpointKind(),
          attempt.getCheckpointKey(),
          attempt.getCheckpointDigest(),
          attempt.getCheckpointCompletedAt());
    }
    return mutations.advanceAcceptedWorkspaceFrontier(
        attempt.getRunId(),
        attempt.getBeforeGeneration(),
        attempt.getTaskIndex() - 1,
        current,
        attempt.getTaskIndex(),
        attempt.getId(),
        attempt.getCheckpointKind(),
        attempt.getCheckpointKey(),
        attempt.getCheckpointDigest(),
        attempt.getCheckpointCompletedAt());
  }
}
