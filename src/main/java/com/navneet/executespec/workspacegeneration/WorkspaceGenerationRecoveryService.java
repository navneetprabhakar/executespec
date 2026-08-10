package com.navneet.executespec.workspacegeneration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Authority;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Lease;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Deterministically rolls unresolved BUILD generations back to their accepted predecessor. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceGenerationRecoveryService {

  static final List<WorkspaceGenerationState> UNRESOLVED =
      List.of(
          WorkspaceGenerationState.PREPARED,
          WorkspaceGenerationState.MUTATING,
          WorkspaceGenerationState.VERIFYING,
          WorkspaceGenerationState.VERIFIED,
          WorkspaceGenerationState.CHECKPOINTING,
          WorkspaceGenerationState.ROLLING_BACK,
          WorkspaceGenerationState.RECOVERY_FAILED);

  private final WorkspaceGenerationAttemptRepository attempts;
  private final CorrectionWorkspaceTransaction workspaces;
  private final RunWorkspaceAuthorityService authorities;
  private final ObjectMapper objectMapper;
  private final PlatformTransactionManager transactionManager;
  private final TaskStatusPersistenceService taskStatuses;
  private final GcsWorkspaceStore gcsStore;
  private final WorkspaceGenerationArchiveService generationArchives;
  private final PipelineRunRepository runs;
  private final WorkspaceIgnoredArtifactGuard ignoredArtifacts;
  private final AgentProperties agentProperties;
  private final WorkspaceGenerationTelemetry telemetry;

  /** Read-only fence used by reviewer, validation and publication admission. */
  public void assertNoUnresolvedAttempt(String runId) {
    assertNoUnresolvedAttempt(runId, "UNSPECIFIED");
  }

  public void assertNoUnresolvedAttempt(String runId, String boundary) {
    var unresolved = attempts.findFirstByRunIdAndStateInOrderByCreatedAtDesc(runId, UNRESOLVED);
    if (unresolved.isPresent()) {
      WorkspaceGenerationAttempt attempt = unresolved.get();
      telemetry.control(
          runId,
          attempt.getTaskId(),
          attempt.getId(),
          "DOWNSTREAM_ADMISSION",
          "DENIED_UNRESOLVED",
          boundary);
      throw new IllegalStateException("WORKSPACE_GENERATION_UNRESOLVED");
    }
  }

  /** Proves the current bytes are exactly the durable accepted BUILD frontier. */
  public void assertAcceptedFrontier(String runId, String currentGeneration) {
    assertAcceptedFrontier(runId, currentGeneration, "UNSPECIFIED");
  }

  public void assertAcceptedFrontier(String runId, String currentGeneration, String boundary) {
    assertNoUnresolvedAttempt(runId, boundary);
    var run =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_RUN_MISSING"));
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
    assertExactAcceptedAttempt(runId, currentGeneration, run, accepted);
  }

  public void assertAcceptedFrontier(String runId, Path workspaceRoot) {
    assertAcceptedFrontier(runId, workspaces.captureGeneration(workspaceRoot));
  }

  public void assertAcceptedFrontier(String runId, Path workspaceRoot, String boundary) {
    assertAcceptedFrontier(runId, workspaces.captureGeneration(workspaceRoot), boundary);
  }

  public void recoverRunOrThrow(String runId) {
    attempts
        .findFirstByRunIdAndStateInOrderByCreatedAtDesc(runId, UNRESOLVED)
        .ifPresent(
            row -> {
              Lease lease =
                  authorities
                      .tryAcquire(runId, Authority.RECOVERY)
                      .orElseThrow(
                          () ->
                              new IllegalStateException("WORKSPACE_GENERATION_RECOVERY_DEFERRED"));
              recover(row, lease);
              WorkspaceGenerationAttempt recovered =
                  attempts
                      .findById(row.getId())
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "WORKSPACE_GENERATION_RECOVERY_ROW_MISSING"));
              if (recovered.getState() != WorkspaceGenerationState.ROLLED_BACK) {
                throw new IllegalStateException(
                    "WORKSPACE_GENERATION_RECOVERY_UNPROVEN: " + recovered.getState());
              }
            });
    ensureAcceptedWorkspaceAvailable(runId);
    reconcileAcceptedTask(runId);
  }

  /** Restores the latest accepted frontier on a replacement node before generic run replay. */
  public void ensureAcceptedWorkspaceAvailable(String runId) {
    PipelineRun run =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_RUN_MISSING"));
    if (!com.navneet.executespec.state.RunMode.BUILD.equalsIgnoreCase(run.getRunMode())
        || run.getAcceptedGenerationAttemptId() == null) {
      return;
    }
    WorkspaceGenerationAttempt accepted =
        attempts
            .findById(run.getAcceptedGenerationAttemptId())
            .orElseThrow(
                () -> new IllegalStateException("WORKSPACE_GENERATION_ACCEPTANCE_MISSING"));
    assertExactAcceptedAttempt(runId, run.getAcceptedWorkspaceGeneration(), run, accepted);
    Path root = currentWorkspaceRoot(accepted.getRunId());
    if (acceptedGenerationPresent(accepted, root)) return;
    Lease lease =
        authorities
            .tryAcquire(runId, Authority.RECOVERY)
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_RECOVERY_DEFERRED"));
    try {
      // A competing recovery may have repaired the tree before this lease was acquired.
      lease.assertOwned();
      if (acceptedGenerationPresent(accepted, root)) return;
      if (!java.nio.file.Files.isDirectory(root)) {
        restoreArchive(
            accepted.getCheckpointKind(),
            accepted.getCheckpointKey(),
            accepted.getCheckpointDigest(),
            accepted.getCandidateGeneration(),
            root,
            accepted.getRunId(),
            lease::assertOwned);
      } else {
        replaceFromAcceptedArchive(accepted, root, lease);
      }
    } finally {
      lease.close();
    }
  }

  private static void assertExactAcceptedAttempt(
      String runId,
      String expectedGeneration,
      PipelineRun run,
      WorkspaceGenerationAttempt accepted) {
    if (expectedGeneration == null
        || accepted.getState() != WorkspaceGenerationState.ACCEPTED
        || !runId.equals(accepted.getRunId())
        || !expectedGeneration.equals(accepted.getCandidateGeneration())
        || !Objects.equals(run.getAcceptedTaskIndex(), accepted.getTaskIndex())
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

  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    recoverAll();
  }

  @Scheduled(
      fixedDelayString = "${execution.workspace-generation.recovery-scan-ms:60000}",
      initialDelayString = "${execution.workspace-generation.recovery-initial-delay-ms:60000}")
  void scheduledRecovery() {
    recoverAll();
  }

  void recoverAll() {
    List<WorkspaceGenerationAttempt> unresolved =
        attempts.findByStateInOrderByCreatedAtAsc(UNRESOLVED);
    telemetry.unresolved(unresolved.size());
    for (WorkspaceGenerationAttempt attempt : unresolved) {
      try {
        authorities
            .tryAcquire(attempt.getRunId(), Authority.RECOVERY)
            .ifPresent(lease -> recover(attempt, lease));
      } catch (RuntimeException failure) {
        log.error(
            "Workspace generation recovery failed run={} attempt={}",
            attempt.getRunId(),
            attempt.getId(),
            failure);
      }
    }
    attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ACCEPTED).stream()
        .map(WorkspaceGenerationAttempt::getRunId)
        .distinct()
        .forEach(this::reconcileAcceptedTask);
    telemetry.unresolved(attempts.findByStateInOrderByCreatedAtAsc(UNRESOLVED).size());
  }

  void recover(WorkspaceGenerationAttempt stale, Lease recoveryLease) {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    boolean recoveryClaimed = false;
    try {
      int claimed =
          transactions.execute(
              ignored ->
                  attempts.claimRecovery(
                      stale.getId(),
                      stale.getState(),
                      WorkspaceGenerationState.ROLLING_BACK,
                      recoveryLease.ownerToken(),
                      Instant.now()));
      requireCas(claimed, "WORKSPACE_GENERATION_RECOVERY_CLAIM_FAILED");
      recoveryClaimed = true;
      WorkspaceGenerationAttempt row = require(stale.getId());
      telemetry.authority("TAKEOVER", Authority.RECOVERY.name());
      telemetry.recovery(
          row.getRunId(),
          row.getTaskId(),
          row.getId(),
          "ROLLBACK",
          "CLAIMED",
          WorkspaceGenerationState.ROLLING_BACK.name());
      CorrectionWorkspaceTransaction.Checkpoint checkpoint = readCheckpoint(row, recoveryLease);
      recoveryLease.assertOwned();
      CorrectionWorkspaceTransaction.DiffEvidence rejected =
          workspaces.diffEvidence(checkpoint, recoveryLease::assertOwned);
      int evidenceRecorded =
          transactions.execute(
              ignored ->
                  attempts.recordRecoveryEvidence(
                      row.getId(),
                      recoveryLease.ownerToken(),
                      WorkspaceGenerationState.ROLLING_BACK,
                      rejected.diffDigest(),
                      rejectedDiffJson(rejected),
                      "INTERRUPTED_GENERATION_RECOVERED",
                      "Recovered an unresolved workspace generation before dependent execution",
                      Instant.now()));
      requireCas(evidenceRecorded, "WORKSPACE_GENERATION_RECOVERY_EVIDENCE_CAS_FAILED");
      workspaces.rollback(checkpoint, recoveryLease::assertOwned);
      ignoredArtifacts.cleanAttemptArtifacts(
          checkpoint, row.getIgnoredArtifactBaselineJson(), recoveryLease::assertOwned);
      String restored = workspaces.currentGeneration(checkpoint, recoveryLease::assertOwned);
      if (!row.getBeforeGeneration().equals(restored)) {
        throw new IllegalStateException("WORKSPACE_GENERATION_RECOVERY_ROLLBACK_UNPROVEN");
      }
      int resolved =
          transactions.execute(
              ignored ->
                  attempts.markRecoveredRolledBack(
                      row.getId(),
                      recoveryLease.ownerToken(),
                      WorkspaceGenerationState.ROLLING_BACK,
                      WorkspaceGenerationState.ROLLED_BACK,
                      Instant.now()));
      requireCas(resolved, "WORKSPACE_GENERATION_RECOVERY_RESOLVE_FAILED");
      taskStatuses.reopenForContinuation(row.getRunId(), row.getTaskId(), null);
      log.warn(
          "Recovered unresolved workspace generation run={} task={} attempt={}",
          row.getRunId(),
          row.getTaskId(),
          row.getId());
      telemetry.recovery(
          row.getRunId(),
          row.getTaskId(),
          row.getId(),
          "ROLLBACK",
          "SUCCEEDED",
          WorkspaceGenerationState.ROLLED_BACK.name());
    } catch (RuntimeException failure) {
      if (recoveryClaimed) {
        try {
          int failed =
              transactions.execute(
                  ignored ->
                      attempts.markRecoveredFailure(
                          stale.getId(),
                          recoveryLease.ownerToken(),
                          "ROLLBACK_PROOF_FAILED",
                          bounded(failure.getMessage()),
                          WorkspaceGenerationState.ROLLING_BACK,
                          WorkspaceGenerationState.RECOVERY_FAILED,
                          Instant.now()));
          requireCas(failed, "WORKSPACE_GENERATION_RECOVERY_FAILURE_CAS_FAILED");
        } catch (RuntimeException persistenceFailure) {
          failure.addSuppressed(persistenceFailure);
        }
      }
      telemetry.recovery(
          stale.getRunId(),
          stale.getTaskId(),
          stale.getId(),
          "ROLLBACK",
          "FAILED",
          recoveryClaimed
              ? WorkspaceGenerationState.RECOVERY_FAILED.name()
              : stale.getState().name());
      throw failure;
    } finally {
      recoveryLease.close();
    }
  }

  /** Closes ACCEPTED -> task COMPLETE crash window from immutable DB acceptance proof. */
  private void reconcileAcceptedTask(String runId) {
    PipelineRun run =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_RUN_MISSING"));
    if (run.getAcceptedGenerationAttemptId() == null) return;
    WorkspaceGenerationAttempt accepted =
        attempts
            .findById(run.getAcceptedGenerationAttemptId())
            .orElseThrow(
                () -> new IllegalStateException("WORKSPACE_GENERATION_ACCEPTANCE_MISSING"));
    assertExactAcceptedAttempt(runId, run.getAcceptedWorkspaceGeneration(), run, accepted);
    taskStatuses.persist(runId, accepted.getTaskId(), "COMPLETE", null, null, null);
  }

  private CorrectionWorkspaceTransaction.Checkpoint readCheckpoint(
      WorkspaceGenerationAttempt attempt, Lease recoveryLease) {
    try {
      Path declaredRoot = currentWorkspaceRoot(attempt.getRunId());
      if (!java.nio.file.Files.isDirectory(declaredRoot)) {
        restoreArchive(
            attempt.getBeforeCheckpointKind(),
            attempt.getBeforeCheckpointKey(),
            attempt.getBeforeCheckpointDigest(),
            attempt.getBeforeGeneration(),
            declaredRoot,
            attempt.getRunId(),
            recoveryLease::assertOwned);
      }
      RepositoryRef[] refs =
          objectMapper.readValue(attempt.getRepositoriesJson(), RepositoryRef[].class);
      Path root = realPath(declaredRoot);
      return new CorrectionWorkspaceTransaction.Checkpoint(
          root,
          Arrays.stream(refs)
              .map(
                  ref -> {
                    Path relative = Path.of(ref.repository());
                    if (relative.isAbsolute()) {
                      throw new IllegalStateException(
                          "WORKSPACE_GENERATION_RECOVERY_REPOSITORY_NOT_RELATIVE");
                    }
                    Path candidate = root.resolve(relative).normalize();
                    if (!candidate.startsWith(root)) {
                      throw new IllegalStateException(
                          "WORKSPACE_GENERATION_RECOVERY_REPOSITORY_ESCAPES_ROOT");
                    }
                    Path repository = realPath(candidate);
                    if (!repository.startsWith(root)) {
                      throw new IllegalStateException(
                          "WORKSPACE_GENERATION_RECOVERY_REPOSITORY_ESCAPES_ROOT");
                    }
                    return new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                        repository, ref.head(), ref.tree());
                  })
              .toList());
    } catch (Exception malformed) {
      throw new IllegalStateException(
          "WORKSPACE_GENERATION_RECOVERY_CHECKPOINT_INVALID", malformed);
    }
  }

  private String rejectedDiffJson(CorrectionWorkspaceTransaction.DiffEvidence diff) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "changedFiles", diff.changedFiles(),
              "unifiedPatch", diff.unifiedPatch(),
              "patchCapturedBytes", diff.patchCapturedBytes(),
              "patchFullBytes", diff.patchFullBytes(),
              "patchTruncated", diff.patchTruncated(),
              "patchRedacted", diff.patchRedacted()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new IllegalStateException(
          "WORKSPACE_GENERATION_RECOVERY_DIFF_SERIALIZATION_FAILED", impossible);
    }
  }

  private void restoreArchive(
      String kind,
      String key,
      String digest,
      String generation,
      Path root,
      String runId,
      Runnable fence) {
    if (!"GCS".equals(kind)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_SAME_HOST_CHECKPOINT_UNAVAILABLE");
    }
    Path archive = null;
    try {
      fence.run();
      archive = gcsStore.downloadToTemp(key, runId);
      fence.run();
      generationArchives.restore(archive, digest, root, generation);
      fence.run();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_DOWNLOAD_FAILED", failure);
    } finally {
      if (archive != null) {
        try {
          java.nio.file.Files.deleteIfExists(archive);
        } catch (java.io.IOException ignored) {
        }
      }
    }
  }

  /** Replaces a stale/partial local tree only after an exact accepted archive is reconstructed. */
  private void replaceFromAcceptedArchive(
      WorkspaceGenerationAttempt accepted, Path root, Lease recoveryLease) {
    Path replacement =
        root.resolveSibling(root.getFileName() + ".accepted-" + java.util.UUID.randomUUID());
    Path displaced =
        root.resolveSibling(root.getFileName() + ".displaced-" + java.util.UUID.randomUUID());
    try {
      restoreArchive(
          accepted.getCheckpointKind(),
          accepted.getCheckpointKey(),
          accepted.getCheckpointDigest(),
          accepted.getCandidateGeneration(),
          replacement,
          accepted.getRunId(),
          recoveryLease::assertOwned);
      recoveryLease.assertOwned();
      move(root, displaced);
      try {
        recoveryLease.assertOwned();
        move(replacement, root);
        recoveryLease.assertOwned();
        String restored = workspaces.captureGeneration(root);
        if (!accepted.getCandidateGeneration().equals(restored)) {
          throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_UNPROVEN");
        }
      } catch (RuntimeException replacementFailure) {
        Path rejected =
            root.resolveSibling(root.getFileName() + ".rejected-" + java.util.UUID.randomUUID());
        if (java.nio.file.Files.exists(root)) move(root, rejected);
        move(displaced, root);
        deleteTree(rejected);
        throw replacementFailure;
      }
      deleteTree(displaced);
    } finally {
      deleteTree(replacement);
    }
  }

  private boolean acceptedGenerationPresent(
      WorkspaceGenerationAttempt accepted, Path workspaceRoot) {
    if (!java.nio.file.Files.isDirectory(workspaceRoot)) return false;
    try {
      return accepted.getCandidateGeneration().equals(workspaces.captureGeneration(workspaceRoot));
    } catch (RuntimeException malformedLocalWorkspace) {
      log.warn(
          "Accepted workspace cannot be attested; reconstructing run={} errorClass={}",
          accepted.getRunId(),
          malformedLocalWorkspace.getClass().getSimpleName());
      return false;
    }
  }

  private static void move(Path source, Path target) {
    try {
      java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      try {
        java.nio.file.Files.move(source, target);
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_MOVE_FAILED", failure);
      }
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_MOVE_FAILED", failure);
    }
  }

  private static void deleteTree(Path root) {
    if (root == null || !java.nio.file.Files.exists(root)) return;
    try (var paths = java.nio.file.Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        java.nio.file.Files.deleteIfExists(path);
      }
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_REPLACEMENT_CLEANUP_FAILED", failure);
    }
  }

  private WorkspaceGenerationAttempt require(java.util.UUID id) {
    return attempts
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_ATTEMPT_NOT_FOUND"));
  }

  private Path currentWorkspaceRoot(String runId) {
    Path base = Path.of(agentProperties.getWorkspace()).toAbsolutePath().normalize();
    Path root = base.resolve(runId).normalize();
    if (!root.startsWith(base)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_RECOVERY_ROOT_ESCAPES_BASE");
    }
    return root;
  }

  private static Path realPath(Path value) {
    try {
      return value.toRealPath();
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException(
          "WORKSPACE_GENERATION_RECOVERY_WORKSPACE_UNAVAILABLE", unavailable);
    }
  }

  private static void requireCas(Integer value, String code) {
    if (value == null || value != 1) throw new IllegalStateException(code);
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "unspecified";
    return value.length() <= 4000 ? value : value.substring(0, 4000);
  }

  private record RepositoryRef(String repository, String head, String tree) {}
}
