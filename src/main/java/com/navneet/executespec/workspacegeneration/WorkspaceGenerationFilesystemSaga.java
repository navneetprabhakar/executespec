package com.navneet.executespec.workspacegeneration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Restart-safe physical rollback of every repository in a workspace generation. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationFilesystemSaga {

  private final WorkspaceGenerationStepStore steps;
  private final CorrectionWorkspaceTransaction workspaces;

  /**
   * Restores repositories in reverse nesting order. Each repository has an immutable intent; a
   * crash after any Git command simply replays the same idempotent restore on takeover.
   */
  public void restoreRepositories(
      UUID attemptId,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      Runnable authorityFence) {
    List<CorrectionWorkspaceTransaction.RepositoryCheckpoint> reverse =
        new ArrayList<>(checkpoint.repositories());
    java.util.Collections.reverse(reverse);
    for (CorrectionWorkspaceTransaction.RepositoryCheckpoint repository : reverse) {
      authorityFence.run();
      String relative = relative(checkpoint.workspaceRoot(), repository.repository());
      String stepKey = "repository:" + relative;
      WorkspaceGenerationStepStore.Step step =
          steps.recordIntent(
              attemptId,
              WorkspaceGenerationStepStore.Kind.ROLLBACK_RESTORE,
              stepKey,
              checkpoint.generation(),
              repository.tree(),
              Map.of(
                  "repository", relative,
                  "head", repository.head(),
                  "tree", repository.tree(),
                  "workspaceGeneration", checkpoint.generation()));
      if (step.state() == WorkspaceGenerationStepStore.State.VERIFIED) continue;
      if (step.state() == WorkspaceGenerationStepStore.State.APPLIED
          && repository
              .tree()
              .equals(workspaces.attestRepository(checkpoint, repository, authorityFence))) {
        steps.markVerified(
            attemptId,
            WorkspaceGenerationStepStore.Kind.ROLLBACK_RESTORE,
            stepKey,
            Map.of("repository", relative, "tree", repository.tree(), "recovered", true));
        continue;
      }
      workspaces.rollbackRepository(checkpoint, repository, authorityFence);
      steps.markApplied(
          attemptId,
          WorkspaceGenerationStepStore.Kind.ROLLBACK_RESTORE,
          stepKey,
          Map.of("repository", relative, "tree", repository.tree()));
      String restored = workspaces.attestRepository(checkpoint, repository, authorityFence);
      if (!repository.tree().equals(restored)) {
        throw new IllegalStateException("WORKSPACE_GENERATION_REPOSITORY_RESTORE_UNPROVEN");
      }
      steps.markVerified(
          attemptId,
          WorkspaceGenerationStepStore.Kind.ROLLBACK_RESTORE,
          stepKey,
          Map.of("repository", relative, "tree", restored, "recovered", false));
    }
    String restored = workspaces.currentGeneration(checkpoint, authorityFence);
    if (!checkpoint.generation().equals(restored)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ROLLBACK_UNPROVEN");
    }
  }

  private static String relative(Path root, Path repository) {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedRepository = repository.toAbsolutePath().normalize();
    if (!normalizedRepository.startsWith(normalizedRoot)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_REPOSITORY_ESCAPES_ROOT");
    }
    String relative = normalizedRoot.relativize(normalizedRepository).toString().replace('\\', '/');
    return relative.isBlank() ? "." : relative;
  }
}
