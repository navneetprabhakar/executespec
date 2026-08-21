package com.navneet.executespec.workspacegeneration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Authority;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Lease;
import com.navneet.executespec.sandbox.SandboxCommandJournalStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunMutationRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/**
 * Transactional state machine for BUILD workspace generations.
 *
 * <p>This service deliberately does not infer success from an agent outcome. It accepts only an
 * attested candidate tree with a server-resolved passing verification receipt and a completed
 * exact-generation checkpoint, then advances the run frontier in the same database transaction.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationLifecycle {

  private static final String SHA_256 = "[0-9a-f]{64}";

  private final WorkspaceGenerationAttemptRepository attempts;
  private final WorkspaceGenerationAdmissionStore admissionStore;
  private final WorkspaceGenerationVerificationReceiptRepository receipts;
  private final PipelineRunRepository runs;
  private final PipelineRunMutationRepository runMutations;
  private final RunWorkspaceAuthorityService workspaceAuthorities;
  private final CorrectionWorkspaceTransaction workspaces;
  private final ObjectMapper objectMapper;
  private final com.navneet.executespec.deployment.GcsTaskCheckpointService checkpointService;
  private final WorkspaceGenerationRecoveryService recoveryService;
  private final WorkspaceGenerationRollbackService rollbackService;
  private final WorkspaceIgnoredArtifactGuard ignoredArtifacts;
  private final BuildEnvironmentReceiptStore environmentReceipts;
  private final WorkspaceGenerationStepStore generationSteps;
  private final SandboxCommandJournalStore commandJournal;
  private final PlatformTransactionManager transactionManager;

  /**
   * Acquires authority, creates the durable before-checkpoint, and advances an already admitted
   * attempt to PREPARED. The attempt and exact LLM operation are persisted before this method.
   */
  public Prepared prepareBuild(
      UUID attemptId,
      Path workspaceRoot,
      String llmOperationId,
      int llmAttemptNumber,
      UUID llmLeaseToken) {
    requireText(llmOperationId, "WORKSPACE_GENERATION_LLM_OPERATION_INVALID");
    if (attemptId == null || llmAttemptNumber <= 0 || llmLeaseToken == null) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_LLM_LEASE_INVALID");
    }
    WorkspaceGenerationAttempt admitted = require(attemptId);
    if (admitted.getState() != WorkspaceGenerationState.OPERATION_CLAIMED
        || !Objects.equals(admitted.getLlmOperationId(), llmOperationId)
        || !Objects.equals(admitted.getLlmAttemptNumber(), llmAttemptNumber)
        || !Objects.equals(admitted.getLlmLeaseToken(), llmLeaseToken)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_OPERATION_BINDING_MISMATCH");
    }
    String runId = admitted.getRunId();
    String taskId = admitted.getTaskId();
    Lease lease = workspaceAuthorities.acquire(runId, Authority.BUILD_GENERATION);
    try {
      admissionStore.acquireAuthority(
          attemptId, lease.ownerToken(), llmOperationId, llmAttemptNumber, llmLeaseToken);
      Path canonicalRoot = WorkspaceGenerationEvidenceCodec.realPath(workspaceRoot);
      if (!canonicalRoot.equals(
          WorkspaceGenerationEvidenceCodec.realPath(Path.of(admitted.getWorkspaceRoot())))) {
        throw new IllegalStateException("WORKSPACE_GENERATION_ROOT_MISMATCH");
      }
      CorrectionWorkspaceTransaction.Checkpoint checkpoint =
          WorkspaceGenerationEvidenceCodec.canonicalCheckpoint(
              canonicalRoot, workspaces.begin(canonicalRoot));
      WorkspaceIgnoredArtifactGuard.Baseline ignoredBaseline =
          ignoredArtifacts.prepare(attemptId, checkpoint, lease::assertOwned);
      String beforeGeneration = checkpoint.generation();
      String beforeStepKey = "before:" + beforeGeneration;
      String beforeObjectKey =
          WorkspaceGenerationEvidenceCodec.archiveObjectKey(
              runId, attemptId, "before", beforeGeneration);
      generationSteps.recordIntent(
          attemptId,
          WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
          beforeStepKey,
          beforeGeneration,
          null,
          Map.of(
              "runId", runId,
              "taskId", taskId,
              "objectKey", beforeObjectKey,
              "generation", beforeGeneration));
      com.navneet.executespec.deployment.GcsTaskCheckpointService.CheckpointReceipt
          beforeCheckpoint =
              checkpointService.checkpointGeneration(
                  runId, taskId + ":before", checkpoint, beforeObjectKey);
      generationSteps.markArchiveApplied(
          attemptId,
          WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
          beforeStepKey,
          WorkspaceGenerationEvidenceCodec.archiveReceipt(beforeCheckpoint));
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      transaction.executeWithoutResult(
          ignored -> {
            seedOrAssertFrontier(runId, admitted.getTaskIndex(), beforeGeneration);
            requireCas(
                attempts.completePreparation(
                    attemptId,
                    lease.ownerToken(),
                    beforeGeneration,
                    WorkspaceGenerationEvidenceCodec.repositories(
                        objectMapper, canonicalRoot, checkpoint),
                    ignoredBaseline.json(),
                    ignoredBaseline.digest(),
                    beforeCheckpoint.kind(),
                    beforeCheckpoint.key(),
                    beforeCheckpoint.artifactDigest(),
                    beforeCheckpoint.storageGeneration(),
                    beforeCheckpoint.sizeBytes(),
                    beforeCheckpoint.crc32c(),
                    beforeCheckpoint.etag(),
                    beforeCheckpoint.completedAt(),
                    WorkspaceGenerationState.AUTHORITY_ACQUIRED,
                    WorkspaceGenerationState.PREPARED,
                    Instant.now()),
                "WORKSPACE_GENERATION_PREPARE_CAS_FAILED");
          });
      generationSteps.markVerified(
          attemptId,
          WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
          beforeStepKey,
          Map.of(
              "attemptState", WorkspaceGenerationState.PREPARED.name(),
              "generation", beforeGeneration,
              "objectKey", beforeCheckpoint.key(),
              "artifactDigest", beforeCheckpoint.artifactDigest()));
      return new Prepared(attemptId, checkpoint, lease);
    } catch (RuntimeException failure) {
      lease.close();
      throw failure;
    }
  }

  /** Admission fence for a replacement BUILD attempt before its task row may be reopened. */
  public void assertContinuationReady(String runId, Path workspaceRoot) {
    recoveryService.recoverRunOrThrow(runId);
    recoveryService.assertAcceptedFrontier(runId, workspaceRoot, "BUILD_CONTINUATION");
  }

  /** Synchronous convergence hook for supervisor failures before a Prepared handle is returned. */
  public void recoverRunOrThrow(String runId) {
    recoveryService.recoverRunOrThrow(runId);
  }

  @Transactional
  public void markMutating(Prepared prepared) {
    assertOwned(prepared);
    requireCas(
        attempts.transition(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            WorkspaceGenerationState.PREPARED,
            WorkspaceGenerationState.MUTATING,
            Instant.now()),
        "WORKSPACE_GENERATION_MUTATING_CAS_FAILED");
  }

  /** Fresh database fence used before every task provider/tool dispatch. */
  @Transactional(readOnly = true)
  public void assertMutationDispatch(Prepared prepared) {
    assertAuthority(prepared, WorkspaceGenerationState.MUTATING);
  }

  /** Exact non-mutating verifier authority after the candidate state leaves MUTATING. */
  public void assertVerificationDispatch(Prepared prepared) {
    assertAuthority(prepared, WorkspaceGenerationState.VERIFYING);
  }

  private void assertAuthority(Prepared prepared, WorkspaceGenerationState expectedState) {
    assertOwned(prepared);
    if (attempts.countCurrentAuthority(
            prepared.attemptId(), prepared.lease().ownerToken(), expectedState.name())
        != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_DISPATCH_AUTHORITY_LOST");
    }
  }

  /**
   * Attests the candidate from Git trees and enters VERIFYING; caller-supplied digests are banned.
   */
  @Transactional
  public String beginVerification(Prepared prepared) {
    // A provider return is not acceptance authority. Repeat the exact run/workspace/operation
    // join after the worker exits so an expired or terminal LLM lease cannot advance its bytes.
    assertMutationDispatch(prepared);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    if (BuildProtocolVersion.SHARED_KERNEL_V2.name().equals(attempt.getBuildProtocolVersion())) {
      environmentReceipts.requirePassed(
          attempt.getId(),
          BuildEnvironmentMaterializer.Phase.INITIAL_PROBE.name(),
          BuildEnvironmentMaterializer.Phase.WORKER.name());
    }
    // Ignored dependencies and build output are not generation evidence and cannot cross into the
    // verifier. Remove them before freezing the candidate; the disposable verifier must
    // deterministically rematerialize what it needs from accepted manifests/runtime kits.
    ignoredArtifacts.cleanAttemptArtifacts(
        prepared.attemptId(),
        "CANDIDATE_CLEANUP",
        prepared.checkpoint(),
        attempt.getIgnoredArtifactBaselineJson(),
        attempt.getIgnoredArtifactBaselineDigest(),
        prepared.lease()::assertOwned);
    String candidate =
        workspaces.currentGeneration(prepared.checkpoint(), prepared.lease()::assertOwned);
    requireHash(candidate, "WORKSPACE_GENERATION_CANDIDATE_INVALID");
    requireCas(
        attempts.beginVerification(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            candidate,
            WorkspaceGenerationState.MUTATING,
            WorkspaceGenerationState.VERIFYING,
            Instant.now()),
        "WORKSPACE_GENERATION_VERIFYING_CAS_FAILED");
    return candidate;
  }

  /**
   * Exact candidate paths used to derive acceptance requirements from reality, not plan wording.
   */
  public List<String> changedFiles(Prepared prepared) {
    assertOwned(prepared);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    if (attempt.getState() != WorkspaceGenerationState.VERIFYING) {
      throw new IllegalStateException("WORKSPACE_GENERATION_NOT_VERIFYING");
    }
    return List.copyOf(
        workspaces
            .diffEvidence(prepared.checkpoint(), prepared.lease()::assertOwned)
            .changedFiles());
  }

  /** Persists immutable evidence and marks VERIFIED only for an exact passing receipt. */
  @Transactional
  public UUID recordVerification(Prepared prepared, Verification result) {
    assertVerificationDispatch(prepared);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    if (attempt.getState() != WorkspaceGenerationState.VERIFYING) {
      throw new IllegalStateException("WORKSPACE_GENERATION_NOT_VERIFYING");
    }
    String current =
        workspaces.currentGeneration(prepared.checkpoint(), prepared.lease()::assertOwned);
    if (!Objects.equals(attempt.getCandidateGeneration(), current)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_CHANGED_DURING_VERIFICATION");
    }
    String evidenceJson =
        WorkspaceGenerationEvidenceCodec.canonicalJson(objectMapper, result.evidenceJson());
    List<UUID> commandReceiptIds =
        WorkspaceGenerationEvidenceCodec.commandReceiptIds(objectMapper, evidenceJson);
    if ("PASSED".equals(result.outcome())
        && attempt.getVerificationCommandProtocolVersion() != null
        && attempt.getVerificationCommandProtocolVersion() >= 2) {
      commandJournal.requireSuccessfulReceipts(attempt.getId(), current, commandReceiptIds);
    }
    if ("PASSED".equals(result.outcome())
        && BuildProtocolVersion.SHARED_KERNEL_V2.name().equals(attempt.getBuildProtocolVersion())) {
      environmentReceipts.requirePassed(
          attempt.getId(), BuildEnvironmentMaterializer.Phase.FINAL_VERIFICATION.name());
    }
    Instant now = Instant.now();
    UUID receiptId = UUID.randomUUID();
    WorkspaceGenerationVerificationReceipt receipt =
        WorkspaceGenerationVerificationReceipt.builder()
            .id(receiptId)
            .attemptId(attempt.getId())
            .receiptVersion(1)
            .candidateGeneration(current)
            .taskContractDigest(attempt.getTaskContractDigest())
            .verifierKind(result.verifierKind())
            .commandSource("SERVER_RESOLVED_CONTRACT")
            .commandDigest(
                WorkspaceGenerationEvidenceCodec.sha256(result.serverResolvedCommandContract()))
            .outcome(result.outcome())
            .failureCategory(result.failureCategory())
            .environmentCode(result.environmentCode())
            .executionBoundary(result.executionBoundary())
            .buildPassed(result.buildPassed())
            .testsExecuted(result.testsExecuted())
            .testsPassed(nonNegative(result.testsPassed(), "testsPassed"))
            .testsFailed(nonNegative(result.testsFailed(), "testsFailed"))
            .testsErrors(nonNegative(result.testsErrors(), "testsErrors"))
            .testsSkipped(nonNegative(result.testsSkipped(), "testsSkipped"))
            .evidenceJson(evidenceJson)
            .evidenceDigest(WorkspaceGenerationEvidenceCodec.sha256(evidenceJson))
            .commandReceiptIds(
                WorkspaceGenerationEvidenceCodec.write(objectMapper, commandReceiptIds))
            .startedAt(result.startedAt())
            .completedAt(result.completedAt())
            .createdAt(now)
            .build();
    WorkspaceGenerationVerificationValidator.validate(result);
    receipts.saveAndFlush(receipt);
    if ("PASSED".equals(result.outcome()) && result.buildPassed()) {
      requireCas(
          attempts.transition(
              attempt.getId(),
              prepared.lease().ownerToken(),
              WorkspaceGenerationState.VERIFYING,
              WorkspaceGenerationState.VERIFIED,
              now),
          "WORKSPACE_GENERATION_VERIFIED_CAS_FAILED");
    }
    return receiptId;
  }

  @Transactional
  public void beginCheckpoint(Prepared prepared) {
    assertAuthority(prepared, WorkspaceGenerationState.VERIFIED);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    ignoredArtifacts.cleanAttemptArtifacts(
        prepared.attemptId(),
        "CHECKPOINT_CLEANUP",
        prepared.checkpoint(),
        attempt.getIgnoredArtifactBaselineJson(),
        attempt.getIgnoredArtifactBaselineDigest(),
        prepared.lease()::assertOwned);
    String current =
        workspaces.currentGeneration(prepared.checkpoint(), prepared.lease()::assertOwned);
    if (!Objects.equals(attempt.getCandidateGeneration(), current)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_CHANGED_BEFORE_CHECKPOINT");
    }
    requireCas(
        attempts.transition(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            WorkspaceGenerationState.VERIFIED,
            WorkspaceGenerationState.CHECKPOINTING,
            Instant.now()),
        "WORKSPACE_GENERATION_CHECKPOINTING_CAS_FAILED");
  }

  /** Returns the exact candidate repository trees consumed by the durable archive transport. */
  public CorrectionWorkspaceTransaction.Checkpoint snapshotCandidate(Prepared prepared) {
    assertAuthority(prepared, WorkspaceGenerationState.CHECKPOINTING);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    if (attempt.getState() != WorkspaceGenerationState.CHECKPOINTING) {
      throw new IllegalStateException("WORKSPACE_GENERATION_NOT_CHECKPOINTING");
    }
    CorrectionWorkspaceTransaction.Checkpoint candidate =
        workspaces.snapshotCurrent(prepared.checkpoint(), prepared.lease()::assertOwned);
    if (!Objects.equals(attempt.getCandidateGeneration(), candidate.generation())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_CHANGED_BEFORE_CHECKPOINT");
    }
    return candidate;
  }

  /** Journals and performs the exact candidate archive write outside any database transaction. */
  public com.navneet.executespec.deployment.GcsTaskCheckpointService.CheckpointReceipt
      checkpointCandidate(
          Prepared prepared, String taskId, CorrectionWorkspaceTransaction.Checkpoint candidate) {
    assertAuthority(prepared, WorkspaceGenerationState.CHECKPOINTING);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    if (!Objects.equals(attempt.getCandidateGeneration(), candidate.generation())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_CHANGED_BEFORE_CHECKPOINT");
    }
    String stepKey = "candidate:" + candidate.generation();
    String objectKey =
        WorkspaceGenerationEvidenceCodec.archiveObjectKey(
            attempt.getRunId(), attempt.getId(), "candidate", candidate.generation());
    generationSteps.recordIntent(
        attempt.getId(),
        WorkspaceGenerationStepStore.Kind.CANDIDATE_ARCHIVE_UPLOAD,
        stepKey,
        candidate.generation(),
        null,
        Map.of(
            "runId",
            attempt.getRunId(),
            "taskId",
            taskId,
            "objectKey",
            objectKey,
            "generation",
            candidate.generation()));
    var receipt =
        checkpointService.checkpointGeneration(attempt.getRunId(), taskId, candidate, objectKey);
    generationSteps.markArchiveApplied(
        attempt.getId(),
        WorkspaceGenerationStepStore.Kind.CANDIDATE_ARCHIVE_UPLOAD,
        stepKey,
        WorkspaceGenerationEvidenceCodec.archiveReceipt(receipt));
    return receipt;
  }

  /** Records acknowledgement from the checkpoint transport for this exact candidate tree. */
  @Transactional
  public void completeCheckpoint(
      Prepared prepared,
      String kind,
      String key,
      String generation,
      String artifactDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag) {
    assertAuthority(prepared, WorkspaceGenerationState.CHECKPOINTING);
    if (!List.of("GCS", "LOCAL_ARCHIVE").contains(kind)) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_CHECKPOINT_KIND_INVALID");
    }
    requireHash(generation, "WORKSPACE_GENERATION_CHECKPOINT_GENERATION_INVALID");
    requireHash(artifactDigest, "WORKSPACE_GENERATION_CHECKPOINT_DIGEST_INVALID");
    requireText(storageGeneration, "WORKSPACE_GENERATION_CHECKPOINT_STORAGE_GENERATION_INVALID");
    if (sizeBytes <= 0) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_CHECKPOINT_SIZE_INVALID");
    }
    requireText(crc32c, "WORKSPACE_GENERATION_CHECKPOINT_CRC32C_INVALID");
    requireText(etag, "WORKSPACE_GENERATION_CHECKPOINT_ETAG_INVALID");
    requireCas(
        attempts.completeCheckpoint(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            kind,
            requireText(key, "WORKSPACE_GENERATION_CHECKPOINT_KEY_INVALID"),
            generation,
            artifactDigest,
            storageGeneration,
            sizeBytes,
            crc32c,
            etag,
            WorkspaceGenerationState.CHECKPOINTING,
            Instant.now()),
        "WORKSPACE_GENERATION_CHECKPOINT_CAS_FAILED");
    generationSteps.markVerified(
        prepared.attemptId(),
        WorkspaceGenerationStepStore.Kind.CANDIDATE_ARCHIVE_UPLOAD,
        "candidate:" + generation,
        Map.of(
            "attemptState", WorkspaceGenerationState.CHECKPOINTING.name(),
            "generation", generation,
            "objectKey", key,
            "artifactDigest", artifactDigest));
  }

  /** Atomically accepts the attempt and advances the run's accepted-generation frontier. */
  @Transactional
  public void accept(Prepared prepared) {
    assertAuthority(prepared, WorkspaceGenerationState.CHECKPOINTING);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    String current =
        workspaces.currentGeneration(prepared.checkpoint(), prepared.lease()::assertOwned);
    if (!Objects.equals(attempt.getCandidateGeneration(), current)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_CHANGED_BEFORE_ACCEPT");
    }
    requireCas(
        attempts.accept(
            attempt.getId(),
            prepared.lease().ownerToken(),
            current,
            WorkspaceGenerationState.CHECKPOINTING,
            WorkspaceGenerationState.ACCEPTED,
            Instant.now()),
        "WORKSPACE_GENERATION_ACCEPT_CAS_FAILED");
    requireCas(
        runMutations.advanceAcceptedWorkspaceFrontier(
            attempt.getRunId(),
            attempt.getBeforeGeneration(),
            attempt.getTaskIndex() - 1,
            current,
            attempt.getTaskIndex(),
            attempt.getId(),
            attempt.getCheckpointKind(),
            attempt.getCheckpointKey(),
            attempt.getCheckpointDigest(),
            attempt.getCheckpointCompletedAt()),
        "WORKSPACE_GENERATION_FRONTIER_CAS_FAILED");
  }

  /** Restores the exact pre-attempt multi-repository trees before resolving the rejection. */
  public void rollback(Prepared prepared, String failureCode, String failureReason) {
    assertOwned(prepared);
    rollbackService.rollback(
        prepared.attemptId(),
        prepared.lease().ownerToken(),
        prepared.checkpoint(),
        prepared.lease()::assertOwned,
        failureCode,
        failureReason);
  }

  /** Releases BUILD authority only after task, outbox, and LLM-operation finalization. */
  public void release(Prepared prepared) {
    if (prepared != null) releaseAfterCompletion(prepared.lease());
  }

  private void seedOrAssertFrontier(String runId, int taskIndex, String beforeGeneration) {
    if (taskIndex == 0) runMutations.seedAcceptedWorkspaceFrontier(runId, beforeGeneration);
    PipelineRun run =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_RUN_NOT_FOUND"));
    if (!Objects.equals(beforeGeneration, run.getAcceptedWorkspaceGeneration())
        || !Objects.equals(taskIndex - 1, run.getAcceptedTaskIndex())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_FRONTIER_MISMATCH");
    }
  }

  private WorkspaceGenerationAttempt require(UUID attemptId) {
    return attempts
        .findById(attemptId)
        .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_ATTEMPT_NOT_FOUND"));
  }

  private static void assertOwned(Prepared prepared) {
    Objects.requireNonNull(prepared, "prepared");
    prepared.lease().assertOwned();
  }

  private static int nonNegative(int value, String label) {
    if (value < 0) throw new IllegalArgumentException(label + " must be non-negative");
    return value;
  }

  private static void requireHash(String value, String code) {
    if (value == null || !value.matches(SHA_256)) throw new IllegalArgumentException(code);
  }

  private static String requireText(String value, String code) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    return value;
  }

  private static void requireCas(int updated, String code) {
    if (updated != 1) throw new IllegalStateException(code);
  }

  private static void releaseAfterCompletion(Lease lease) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      lease.close();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            lease.close();
          }
        });
  }

  public record Prepared(
      UUID attemptId, CorrectionWorkspaceTransaction.Checkpoint checkpoint, Lease lease) {}

  public record Verification(
      String verifierKind,
      String serverResolvedCommandContract,
      String outcome,
      String failureCategory,
      String environmentCode,
      String executionBoundary,
      boolean buildPassed,
      boolean testsExecuted,
      int testsPassed,
      int testsFailed,
      int testsErrors,
      int testsSkipped,
      String evidenceJson,
      String requiredProofStrength,
      String achievedProofStrength,
      Instant startedAt,
      Instant completedAt) {}
}
