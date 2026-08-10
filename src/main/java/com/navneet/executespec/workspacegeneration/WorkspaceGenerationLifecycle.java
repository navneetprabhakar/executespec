package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Authority;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService.Lease;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunMutationRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.apache.commons.codec.digest.DigestUtils;
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
  private final WorkspaceGenerationVerificationReceiptRepository receipts;
  private final PipelineRunRepository runs;
  private final PipelineRunMutationRepository runMutations;
  private final RunWorkspaceAuthorityService workspaceAuthorities;
  private final CorrectionWorkspaceTransaction workspaces;
  private final ObjectMapper objectMapper;
  private final com.navneet.executespec.deployment.GcsTaskCheckpointService checkpointService;
  private final WorkspaceGenerationRecoveryService recoveryService;
  private final WorkspaceIgnoredArtifactGuard ignoredArtifacts;
  private final PlatformTransactionManager transactionManager;

  /** Creates the durable PREPARED row before any BUILD task may dispatch a provider or tool. */
  public Prepared prepareBuild(
      String runId,
      String taskId,
      int taskIndex,
      Path workspaceRoot,
      String authorityDigest,
      String taskContractDigest,
      String taskContractJson,
      String initialFailureEvidenceJson,
      String requestedProvider,
      String requestedModel) {
    requireHash(authorityDigest, "WORKSPACE_GENERATION_AUTHORITY_DIGEST_INVALID");
    requireHash(taskContractDigest, "WORKSPACE_GENERATION_TASK_CONTRACT_DIGEST_INVALID");
    if (taskIndex < 0)
      throw new IllegalArgumentException("WORKSPACE_GENERATION_TASK_INDEX_INVALID");

    recoveryService.recoverRunOrThrow(runId);
    Lease lease = workspaceAuthorities.acquire(runId, Authority.BUILD_GENERATION);
    try {
      Path canonicalRoot = realPath(workspaceRoot);
      CorrectionWorkspaceTransaction.Checkpoint checkpoint =
          canonicalCheckpoint(canonicalRoot, workspaces.begin(canonicalRoot));
      WorkspaceIgnoredArtifactGuard.Baseline ignoredBaseline =
          ignoredArtifacts.prepare(checkpoint, lease::assertOwned);
      String beforeGeneration = checkpoint.generation();
      com.navneet.executespec.deployment.GcsTaskCheckpointService.CheckpointReceipt
          beforeCheckpoint =
              checkpointService.checkpointGeneration(runId, taskId + ":before", checkpoint);

      Instant now = Instant.now();
      WorkspaceGenerationAttempt attempt =
          WorkspaceGenerationAttempt.builder()
              .id(UUID.randomUUID())
              .runId(runId)
              .taskId(taskId)
              .taskIndex(taskIndex)
              .attemptNumber(attempts.maximumAttemptNumber(runId, taskId) + 1)
              .attemptKind(WorkspaceGenerationKind.BUILD_TASK)
              .state(WorkspaceGenerationState.PREPARED)
              .workspaceRoot(canonicalRoot.toString())
              .beforeGeneration(beforeGeneration)
              .repositoriesJson(writeRepositories(canonicalRoot, checkpoint))
              .ignoredArtifactBaselineJson(ignoredBaseline.json())
              .ignoredArtifactBaselineDigest(ignoredBaseline.digest())
              .authorityDigest(authorityDigest)
              .taskContractDigest(taskContractDigest)
              .taskContractJson(
                  taskContractJson == null || taskContractJson.isBlank()
                      ? null
                      : canonicalJson(taskContractJson))
              .initialFailureEvidenceJson(
                  initialFailureEvidenceJson == null || initialFailureEvidenceJson.isBlank()
                      ? null
                      : canonicalJson(initialFailureEvidenceJson))
              .requestedProvider(
                  requestedProvider == null || requestedProvider.isBlank()
                      ? null
                      : requestedProvider)
              .requestedModel(
                  requestedModel == null || requestedModel.isBlank() ? null : requestedModel)
              .workspaceLeaseToken(lease.ownerToken())
              .beforeCheckpointKind(beforeCheckpoint.kind())
              .beforeCheckpointKey(beforeCheckpoint.key())
              .beforeCheckpointDigest(beforeCheckpoint.artifactDigest())
              .beforeCheckpointCompletedAt(beforeCheckpoint.completedAt())
              .createdAt(now)
              .updatedAt(now)
              .build();
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      transaction.executeWithoutResult(
          ignored -> {
            seedOrAssertFrontier(runId, taskIndex, beforeGeneration);
            attempts.saveAndFlush(attempt);
          });
      return new Prepared(attempt.getId(), checkpoint, lease);
    } catch (RuntimeException failure) {
      lease.close();
      throw failure;
    }
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

  /** Binds the exact current per-task LLM operation lease before the first provider call. */
  @Transactional
  public void bindLlmAttempt(
      Prepared prepared, String operationId, int attemptNumber, UUID llmLeaseToken) {
    assertOwned(prepared);
    if (attemptNumber <= 0 || llmLeaseToken == null) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_LLM_LEASE_INVALID");
    }
    requireCas(
        attempts.bindLlmAttempt(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            requireText(operationId, "WORKSPACE_GENERATION_LLM_OPERATION_INVALID"),
            attemptNumber,
            llmLeaseToken,
            WorkspaceGenerationState.MUTATING,
            Instant.now()),
        "WORKSPACE_GENERATION_LLM_BIND_CAS_FAILED");
  }

  /** Fresh database fence used before every task provider/tool dispatch. */
  @Transactional(readOnly = true)
  public void assertDispatch(Prepared prepared) {
    assertOwned(prepared);
    if (attempts.countCurrentDispatchAuthority(prepared.attemptId(), prepared.lease().ownerToken())
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
    assertDispatch(prepared);
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
    assertDispatch(prepared);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    if (attempt.getState() != WorkspaceGenerationState.VERIFYING) {
      throw new IllegalStateException("WORKSPACE_GENERATION_NOT_VERIFYING");
    }
    String current =
        workspaces.currentGeneration(prepared.checkpoint(), prepared.lease()::assertOwned);
    if (!Objects.equals(attempt.getCandidateGeneration(), current)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_CHANGED_DURING_VERIFICATION");
    }
    String evidenceJson = canonicalJson(result.evidenceJson());
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
            .commandDigest(sha256(result.serverResolvedCommandContract()))
            .outcome(result.outcome())
            .buildPassed(result.buildPassed())
            .testsExecuted(result.testsExecuted())
            .testsPassed(nonNegative(result.testsPassed(), "testsPassed"))
            .testsFailed(nonNegative(result.testsFailed(), "testsFailed"))
            .testsErrors(nonNegative(result.testsErrors(), "testsErrors"))
            .testsSkipped(nonNegative(result.testsSkipped(), "testsSkipped"))
            .evidenceJson(evidenceJson)
            .evidenceDigest(sha256(evidenceJson))
            .startedAt(result.startedAt())
            .completedAt(result.completedAt())
            .createdAt(now)
            .build();
    validateReceipt(result);
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
    assertDispatch(prepared);
    WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
    ignoredArtifacts.cleanAttemptArtifacts(
        prepared.checkpoint(),
        attempt.getIgnoredArtifactBaselineJson(),
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
    assertDispatch(prepared);
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

  /** Records acknowledgement from the checkpoint transport for this exact candidate tree. */
  @Transactional
  public void completeCheckpoint(
      Prepared prepared, String kind, String key, String generation, String artifactDigest) {
    assertDispatch(prepared);
    if (!List.of("GCS", "LOCAL_SAME_HOST").contains(kind)) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_CHECKPOINT_KIND_INVALID");
    }
    requireHash(generation, "WORKSPACE_GENERATION_CHECKPOINT_GENERATION_INVALID");
    requireHash(artifactDigest, "WORKSPACE_GENERATION_CHECKPOINT_DIGEST_INVALID");
    requireCas(
        attempts.completeCheckpoint(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            kind,
            requireText(key, "WORKSPACE_GENERATION_CHECKPOINT_KEY_INVALID"),
            generation,
            artifactDigest,
            WorkspaceGenerationState.CHECKPOINTING,
            Instant.now()),
        "WORKSPACE_GENERATION_CHECKPOINT_CAS_FAILED");
  }

  /** Atomically accepts the attempt and advances the run's accepted-generation frontier. */
  @Transactional
  public void accept(Prepared prepared) {
    try {
      assertDispatch(prepared);
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
    } finally {
      releaseAfterCompletion(prepared.lease());
    }
  }

  /** Restores the exact pre-attempt multi-repository trees before resolving the rejection. */
  @Transactional
  public void rollback(Prepared prepared, String failureCode, String failureReason) {
    try {
      assertOwned(prepared);
      WorkspaceGenerationAttempt attempt = require(prepared.attemptId());
      CorrectionWorkspaceTransaction.DiffEvidence diff =
          workspaces.diffEvidence(prepared.checkpoint(), prepared.lease()::assertOwned);
      String diffJson =
          canonicalJson(
              write(
                  Map.of(
                      "changedFiles", diff.changedFiles(),
                      "unifiedPatch", diff.unifiedPatch(),
                      "patchCapturedBytes", diff.patchCapturedBytes(),
                      "patchFullBytes", diff.patchFullBytes(),
                      "patchTruncated", diff.patchTruncated(),
                      "patchRedacted", diff.patchRedacted())));
      requireCas(
          attempts.beginRollback(
              attempt.getId(),
              prepared.lease().ownerToken(),
              attempt.getState(),
              WorkspaceGenerationState.ROLLING_BACK,
              diff.diffDigest(),
              diffJson,
              requireText(failureCode, "WORKSPACE_GENERATION_FAILURE_CODE_INVALID"),
              boundedReason(failureReason),
              Instant.now()),
          "WORKSPACE_GENERATION_ROLLBACK_CAS_FAILED");
      workspaces.rollback(prepared.checkpoint(), prepared.lease()::assertOwned);
      ignoredArtifacts.cleanAttemptArtifacts(
          prepared.checkpoint(),
          attempt.getIgnoredArtifactBaselineJson(),
          prepared.lease()::assertOwned);
      String restored =
          workspaces.currentGeneration(prepared.checkpoint(), prepared.lease()::assertOwned);
      if (!attempt.getBeforeGeneration().equals(restored)) {
        throw new IllegalStateException("WORKSPACE_GENERATION_ROLLBACK_UNPROVEN");
      }
      requireCas(
          attempts.markRolledBack(
              attempt.getId(),
              prepared.lease().ownerToken(),
              WorkspaceGenerationState.ROLLING_BACK,
              WorkspaceGenerationState.ROLLED_BACK,
              Instant.now()),
          "WORKSPACE_GENERATION_ROLLED_BACK_CAS_FAILED");
    } finally {
      // If filesystem restoration throws, this transaction rolls the state update back to its
      // prior unresolved phase. Recovery can then claim and replay the persisted checkpoint.
      releaseAfterCompletion(prepared.lease());
    }
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

  private static void validateReceipt(Verification result) {
    if (!List.of("BUILD_AND_TEST", "BUILD_ONLY", "TEST_ONLY").contains(result.verifierKind())) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_KIND_INVALID");
    }
    if (!List.of("PASSED", "FAILED", "UNAVAILABLE").contains(result.outcome())) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_OUTCOME_INVALID");
    }
    boolean buildOnly = "BUILD_ONLY".equals(result.verifierKind());
    if (buildOnly == result.testsExecuted()) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_KIND_INCONSISTENT");
    }
    if (!result.testsExecuted()
        && (result.testsPassed() != 0
            || result.testsFailed() != 0
            || result.testsErrors() != 0
            || result.testsSkipped() != 0)) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_COUNTS_INCONSISTENT");
    }
    if ("PASSED".equals(result.outcome())
        && (!result.buildPassed()
            || result.testsFailed() != 0
            || result.testsErrors() != 0
            || (result.testsExecuted() && result.testsPassed() <= 0))) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_PASSING_RECEIPT_INVALID");
    }
    if (result.startedAt() == null
        || result.completedAt() == null
        || result.completedAt().isBefore(result.startedAt())) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_VERIFIER_TIME_INVALID");
    }
    requireText(
        result.serverResolvedCommandContract(),
        "WORKSPACE_GENERATION_SERVER_COMMAND_CONTRACT_INVALID");
  }

  private static int nonNegative(int value, String label) {
    if (value < 0) throw new IllegalArgumentException(label + " must be non-negative");
    return value;
  }

  private String writeRepositories(
      Path workspaceRoot, CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return write(
        checkpoint.repositories().stream()
            .map(
                repository ->
                    Map.of(
                        "repository", workspaceRoot.relativize(repository.repository()).toString(),
                        "head", repository.head(),
                        "tree", repository.tree()))
            .toList());
  }

  private static CorrectionWorkspaceTransaction.Checkpoint canonicalCheckpoint(
      Path canonicalRoot, CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return new CorrectionWorkspaceTransaction.Checkpoint(
        canonicalRoot,
        checkpoint.repositories().stream()
            .map(
                repository -> {
                  Path canonicalRepository = realPath(repository.repository());
                  if (!canonicalRepository.startsWith(canonicalRoot)) {
                    throw new IllegalStateException("WORKSPACE_GENERATION_REPOSITORY_ESCAPES_ROOT");
                  }
                  return new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                      canonicalRepository, repository.head(), repository.tree());
                })
            .toList());
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException("WORKSPACE_GENERATION_PATH_UNAVAILABLE", unavailable);
    }
  }

  private String canonicalJson(String value) {
    try {
      return objectMapper.writeValueAsString(
          objectMapper.readTree(requireText(value, "JSON_EMPTY")));
    } catch (JsonProcessingException malformed) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_JSON_INVALID", malformed);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("WORKSPACE_GENERATION_JSON_WRITE_FAILED", impossible);
    }
  }

  private static String sha256(String value) {
    return DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireHash(String value, String code) {
    if (value == null || !value.matches(SHA_256)) throw new IllegalArgumentException(code);
  }

  private static String requireText(String value, String code) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    return value;
  }

  private static String boundedReason(String reason) {
    if (reason == null || reason.isBlank()) return "unspecified";
    return reason.length() <= 4000 ? reason : reason.substring(0, 4000);
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
      boolean buildPassed,
      boolean testsExecuted,
      int testsPassed,
      int testsFailed,
      int testsErrors,
      int testsSkipped,
      String evidenceJson,
      Instant startedAt,
      Instant completedAt) {}
}
