package com.navneet.executespec.workspacegeneration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Deterministically rolls unresolved BUILD generations back to their accepted predecessor. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceGenerationRecoveryService {

  static final int MAX_RECOVERY_ATTEMPTS = WorkspaceGenerationRecoveryFailurePolicy.MAX_ATTEMPTS;

  static final List<WorkspaceGenerationState> UNRESOLVED =
      WorkspaceGenerationRecoveryScope.UNRESOLVED;

  private final WorkspaceGenerationAttemptRepository attempts;
  private final CorrectionWorkspaceTransaction workspaces;
  private final RunWorkspaceAuthorityService authorities;
  private final ObjectMapper objectMapper;
  private final PlatformTransactionManager transactionManager;
  private final WorkspaceGenerationTerminalReconciler terminalReconciler;
  private final WorkspaceGenerationCheckpointRestorer checkpointRestorer;
  private final PipelineRunRepository runs;
  private final WorkspaceIgnoredArtifactGuard ignoredArtifacts;
  private final AgentProperties agentProperties;
  private final WorkspaceGenerationTelemetry telemetry;
  private final WorkspaceGenerationRecoveryArtifactJanitor recoveryArtifactJanitor;
  private final WorkspaceGenerationFilesystemSaga filesystemSaga;
  private final WorkspaceGenerationStepStore generationSteps;
  private final com.navneet.executespec.deployment.GcsTaskCheckpointService checkpointService;

  public enum CancellationConvergence {
    CONVERGED,
    PENDING
  }

  /** Starts cancellation convergence immediately; the scheduled scanner owns any deferred retry. */
  public CancellationConvergence convergeCancelledRun(String runId) {
    try {
      recoverRunOrThrow(runId);
      return CancellationConvergence.CONVERGED;
    } catch (RuntimeException deferredOrFailed) {
      // Exhaustion deliberately throws the failed rollback proof after it has durably quarantined
      // and reconciled the attempt. For a cancelled parent, FAILED cannot overwrite CANCELLED; an
      // empty unresolved set is therefore converged cancellation, not another scheduled retry.
      try {
        if (attempts.findFirstUnresolvedByRunId(runId).isEmpty()) {
          return CancellationConvergence.CONVERGED;
        }
      } catch (RuntimeException convergenceReadFailure) {
        deferredOrFailed.addSuppressed(convergenceReadFailure);
      }
      log.warn(
          "Cancelled BUILD generation convergence remains pending run={} errorCode={}",
          runId,
          bounded(deferredOrFailed.getMessage()));
      return CancellationConvergence.PENDING;
    }
  }

  public void assertNoUnresolvedAttempt(String runId) {
    assertNoUnresolvedAttempt(runId, "UNSPECIFIED");
  }

  public void assertNoUnresolvedAttempt(String runId, String boundary) {
    var unresolved = attempts.findFirstUnresolvedByRunId(runId);
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
    terminalReconciler.assertConverged(runId);
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
    WorkspaceGenerationFrontierVerifier.assertAccepted(
        runId, currentGeneration, run, attempts, telemetry, boundary);
  }

  /** Retry-only authority for a restored first attempt; this is never publication authority. */
  public void assertContinuationFrontier(String runId, Path workspaceRoot) {
    assertNoUnresolvedAttempt(runId, "BUILD_CONTINUATION");
    PipelineRun run = runs.findById(runId).orElseThrow();
    if (run.getAcceptedGenerationAttemptId() != null) {
      assertAcceptedFrontier(runId, workspaceRoot, "BUILD_CONTINUATION");
      return;
    }
    WorkspaceGenerationInitialRetryProof.require(
        run,
        workspaces.captureGeneration(workspaceRoot),
        attempts.findTop20ByRunIdOrderByCreatedAtDesc(runId));
  }

  public void assertAcceptedFrontier(String runId, Path workspaceRoot) {
    assertAcceptedFrontier(runId, workspaces.captureGeneration(workspaceRoot));
  }

  public void assertAcceptedFrontier(String runId, Path workspaceRoot, String boundary) {
    assertAcceptedFrontier(runId, workspaces.captureGeneration(workspaceRoot), boundary);
  }

  public void recoverRunOrThrow(String runId) {
    attempts
        .findFirstUnresolvedByRunId(runId)
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
    terminalReconciler.reconcileRun(runId);
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
    WorkspaceGenerationAcceptanceProof.assertExact(
        runId, run.getAcceptedWorkspaceGeneration(), run, accepted);
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
      replaceFromAcceptedArchive(accepted, root, lease);
    } finally {
      lease.close();
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
    List<WorkspaceGenerationAttempt> unresolved = attempts.findRecoverableAt(Instant.now());
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
    terminalReconciler.reconcileAllKnownTerminalRuns();
    telemetry.unresolved(Math.toIntExact(attempts.countUnresolved()));
  }

  void recover(WorkspaceGenerationAttempt stale, Lease recoveryLease) {
    TransactionTemplate transactions = independentTransactions();
    boolean recoveryClaimed = false;
    boolean rolledBack = false;
    boolean recoveryLeaseClosed = false;
    try {
      Instant claimAt = Instant.now();
      int claimed =
          transactions.execute(
              ignored ->
                  attempts.claimRecovery(
                      stale.getId(),
                      stale.getState(),
                      WorkspaceGenerationState.ROLLING_BACK,
                      recoveryLease.ownerToken(),
                      UUID.randomUUID(),
                      claimAt));
      requireCas(claimed, "WORKSPACE_GENERATION_RECOVERY_CLAIM_FAILED");
      recoveryClaimed = true;
      WorkspaceGenerationAttempt row = require(stale.getId());
      reconcileInterruptedArchive(stale, recoveryLease);
      telemetry.authority("TAKEOVER", Authority.RECOVERY.name());
      telemetry.recovery(
          row.getRunId(),
          row.getTaskId(),
          row.getId(),
          "ROLLBACK",
          "CLAIMED",
          WorkspaceGenerationState.ROLLING_BACK.name());
      if (preDispatch(stale.getState())) {
        int resolved =
            transactions.execute(
                ignored ->
                    attempts.markPreDispatchRolledBack(
                        row.getId(),
                        recoveryLease.ownerToken(),
                        WorkspaceGenerationState.ROLLING_BACK,
                        WorkspaceGenerationState.ROLLED_BACK,
                        Instant.now()));
        requireCas(resolved, "WORKSPACE_GENERATION_PRE_DISPATCH_RECOVERY_FAILED");
        rolledBack = true;
        telemetry.recovery(
            row.getRunId(),
            row.getTaskId(),
            row.getId(),
            "PRE_DISPATCH",
            "SUCCEEDED",
            WorkspaceGenerationState.ROLLED_BACK.name());
        terminalReconciler.reconcileRolledBack(require(row.getId()));
        return;
      }
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
      filesystemSaga.restoreRepositories(row.getId(), checkpoint, recoveryLease::assertOwned);
      ignoredArtifacts.cleanAttemptArtifacts(
          checkpoint,
          row.getIgnoredArtifactBaselineJson(),
          row.getIgnoredArtifactBaselineDigest(),
          recoveryLease::assertOwned);
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
      rolledBack = true;
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
          var disposition =
              new WorkspaceGenerationRecoveryFailurePolicy(attempts)
                  .persist(stale.getId(), recoveryLease.ownerToken(), failure, transactions);
          if (disposition.exhausted()) {
            // Terminal reconciliation is database-fenced while any workspace authority exists.
            // Release RECOVERY only after the exhausted receipt is durable, then publish task,
            // operation, and parent-run terminal state in that order.
            recoveryLease.close();
            recoveryLeaseClosed = true;
            terminalReconciler.reconcileRun(stale.getRunId());
            runs.claimTerminalTransitionWithReasons(
                stale.getRunId(),
                "FAILED",
                "WORKSPACE_GENERATION_RECOVERY_EXHAUSTED",
                "Workspace recovery could not prove rollback; support id "
                    + require(stale.getId()).getRecoverySupportId(),
                disposition.failedAt());
          }
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
      if (!recoveryLeaseClosed) recoveryLease.close();
    }
    if (rolledBack) {
      terminalReconciler.reconcileRolledBack(require(stale.getId()));
    }
  }

  /**
   * Closes the upload-before-receipt crash interval by replaying the deterministic create-only
   * object write while the original candidate bytes still exist. A later rollback may orphan this
   * verified object, but it can no longer be an unidentifiable physical effect.
   */
  private void reconcileInterruptedArchive(WorkspaceGenerationAttempt stale, Lease recoveryLease) {
    WorkspaceGenerationStepStore.Kind kind;
    if (stale.getState() == WorkspaceGenerationState.AUTHORITY_ACQUIRED) {
      kind = WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD;
    } else if (stale.getState() == WorkspaceGenerationState.CHECKPOINTING) {
      kind = WorkspaceGenerationStepStore.Kind.CANDIDATE_ARCHIVE_UPLOAD;
    } else {
      return;
    }
    List<WorkspaceGenerationStepStore.Step> incomplete =
        generationSteps.incomplete(stale.getId(), kind);
    if (incomplete.isEmpty()) return;
    if (incomplete.size() != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_STEP_AMBIGUOUS");
    }
    WorkspaceGenerationStepStore.Step step = incomplete.getFirst();
    Path root = currentWorkspaceRoot(stale.getRunId());
    recoveryLease.assertOwned();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = workspaces.begin(root);
    if (!step.expectedGeneration().equals(checkpoint.generation())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_REPLAY_GENERATION_MISMATCH");
    }
    String objectKey;
    try {
      objectKey = objectMapper.readTree(step.evidenceJson()).path("objectKey").asText();
    } catch (Exception malformed) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_STEP_INVALID", malformed);
    }
    var receipt =
        checkpointService.checkpointGeneration(
            stale.getRunId(), stale.getTaskId() + ":recovery", checkpoint, objectKey);
    generationSteps.markArchiveApplied(
        stale.getId(),
        kind,
        step.stepKey(),
        new WorkspaceGenerationStepStore.ArchiveReceipt(
            receipt.key(),
            receipt.artifactDigest(),
            receipt.storageGeneration(),
            receipt.sizeBytes(),
            receipt.crc32c(),
            receipt.etag()));
    generationSteps.markVerified(
        stale.getId(),
        kind,
        step.stepKey(),
        Map.of(
            "generation", step.expectedGeneration(),
            "artifactDigest", receipt.artifactDigest(),
            "recoveredBeforeRollback", true));
  }

  private static boolean preDispatch(WorkspaceGenerationState state) {
    return state == WorkspaceGenerationState.ADMITTED
        || state == WorkspaceGenerationState.OPERATION_CLAIMED
        || state == WorkspaceGenerationState.AUTHORITY_ACQUIRED;
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
            attempt.getBeforeCheckpointStorageGeneration(),
            attempt.getBeforeCheckpointSizeBytes(),
            attempt.getBeforeCheckpointCrc32c(),
            attempt.getBeforeCheckpointEtag(),
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
      String storageGeneration,
      Long sizeBytes,
      String crc32c,
      String etag,
      String generation,
      Path root,
      String runId,
      Runnable fence) {
    checkpointRestorer.restore(
        kind,
        key,
        digest,
        storageGeneration,
        sizeBytes,
        crc32c,
        etag,
        generation,
        root,
        runId,
        fence);
  }

  /** Replaces a stale/partial local tree only after an exact accepted archive is reconstructed. */
  private void replaceFromAcceptedArchive(
      WorkspaceGenerationAttempt accepted, Path root, Lease recoveryLease) {
    new WorkspaceGenerationAcceptedReplacementSaga(
            checkpointRestorer, workspaces, generationSteps, recoveryArtifactJanitor)
        .ensureAccepted(accepted, root, recoveryLease::assertOwned);
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

  private TransactionTemplate independentTransactions() {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "unspecified";
    return value.length() <= 4000 ? value : value.substring(0, 4000);
  }

  private record RepositoryRef(String repository, String head, String tree) {}
}
