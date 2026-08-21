package com.navneet.executespec.workspacegeneration;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

/** Deterministic, restart-safe installation of an accepted archive on a replacement node. */
final class WorkspaceGenerationAcceptedReplacementSaga {

  @FunctionalInterface
  interface MoveObserver {
    void afterMove(String label, Path source, Path target);
  }

  private final WorkspaceGenerationCheckpointRestorer restorer;
  private final CorrectionWorkspaceTransaction workspaces;
  private final WorkspaceGenerationStepStore steps;
  private final WorkspaceGenerationRecoveryArtifactJanitor cleanup;
  private final MoveObserver moves;

  WorkspaceGenerationAcceptedReplacementSaga(
      WorkspaceGenerationCheckpointRestorer restorer,
      CorrectionWorkspaceTransaction workspaces,
      WorkspaceGenerationStepStore steps,
      WorkspaceGenerationRecoveryArtifactJanitor cleanup) {
    this(restorer, workspaces, steps, cleanup, (label, source, target) -> {});
  }

  WorkspaceGenerationAcceptedReplacementSaga(
      WorkspaceGenerationCheckpointRestorer restorer,
      CorrectionWorkspaceTransaction workspaces,
      WorkspaceGenerationStepStore steps,
      WorkspaceGenerationRecoveryArtifactJanitor cleanup,
      MoveObserver moves) {
    this.restorer = restorer;
    this.workspaces = workspaces;
    this.steps = steps;
    this.cleanup = cleanup;
    this.moves = moves;
  }

  void ensureAccepted(WorkspaceGenerationAttempt accepted, Path root, Runnable authorityFence) {
    String stepKey = "workspace-root";
    WorkspaceGenerationStepStore.Step step =
        steps.recordIntent(
            accepted.getId(),
            WorkspaceGenerationStepStore.Kind.ACCEPTED_REPLACEMENT,
            stepKey,
            accepted.getCandidateGeneration(),
            null,
            Map.of(
                "runId", accepted.getRunId(),
                "checkpointKeyDigest",
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                        accepted.getCheckpointKey())));
    Path replacement = root.resolveSibling(root.getFileName() + ".accepted-" + accepted.getId());
    Path displaced = root.resolveSibling(root.getFileName() + ".displaced-" + accepted.getId());
    if (generationPresent(accepted, root)) {
      settle(accepted, step, stepKey, true);
      enqueue(accepted.getRunId(), displaced);
      enqueue(accepted.getRunId(), replacement);
      return;
    }
    authorityFence.run();
    if (!generationPresent(accepted, replacement)) {
      if (Files.exists(replacement)) {
        throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_STAGING_CONFLICT");
      }
      restorer.restore(
          accepted.getCheckpointKind(),
          accepted.getCheckpointKey(),
          accepted.getCheckpointDigest(),
          accepted.getCheckpointStorageGeneration(),
          accepted.getCheckpointSizeBytes(),
          accepted.getCheckpointCrc32c(),
          accepted.getCheckpointEtag(),
          accepted.getCandidateGeneration(),
          replacement,
          accepted.getRunId(),
          authorityFence);
    }
    authorityFence.run();
    if (Files.exists(root)) {
      if (Files.exists(displaced)) {
        throw new IllegalStateException("WORKSPACE_GENERATION_DISPLACED_STAGING_CONFLICT");
      }
      move("DISPLACE_CURRENT", root, displaced);
    }
    authorityFence.run();
    if (!Files.exists(root)) move("INSTALL_ACCEPTED", replacement, root);
    authorityFence.run();
    String restored = workspaces.captureGeneration(root);
    if (!accepted.getCandidateGeneration().equals(restored)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_UNPROVEN");
    }
    steps.markApplied(
        accepted.getId(),
        WorkspaceGenerationStepStore.Kind.ACCEPTED_REPLACEMENT,
        stepKey,
        Map.of("generation", restored, "replayed", false));
    steps.markVerified(
        accepted.getId(),
        WorkspaceGenerationStepStore.Kind.ACCEPTED_REPLACEMENT,
        stepKey,
        Map.of("generation", restored));
    enqueue(accepted.getRunId(), displaced);
    enqueue(accepted.getRunId(), replacement);
  }

  private void settle(
      WorkspaceGenerationAttempt accepted,
      WorkspaceGenerationStepStore.Step step,
      String stepKey,
      boolean replayed) {
    if (step.state() == WorkspaceGenerationStepStore.State.INTENT) {
      steps.markApplied(
          accepted.getId(),
          WorkspaceGenerationStepStore.Kind.ACCEPTED_REPLACEMENT,
          stepKey,
          Map.of("generation", accepted.getCandidateGeneration(), "replayed", replayed));
    }
    steps.markVerified(
        accepted.getId(),
        WorkspaceGenerationStepStore.Kind.ACCEPTED_REPLACEMENT,
        stepKey,
        Map.of("generation", accepted.getCandidateGeneration()));
  }

  private boolean generationPresent(WorkspaceGenerationAttempt accepted, Path root) {
    if (!Files.isDirectory(root)) return false;
    try {
      return accepted.getCandidateGeneration().equals(workspaces.captureGeneration(root));
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private void move(String label, Path source, Path target) {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      try {
        Files.move(source, target);
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_MOVE_FAILED", failure);
      }
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_MOVE_FAILED", failure);
    }
    moves.afterMove(label, source, target);
  }

  private void enqueue(String runId, Path artifact) {
    try {
      cleanup.enqueue(runId, artifact);
    } catch (RuntimeException ignored) {
      // The deterministic path remains discoverable by the next accepted-replacement replay.
    }
  }
}
