package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/** Persists rejection evidence before restoring a BUILD candidate's filesystem checkpoint. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationRollbackService {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final CorrectionWorkspaceTransaction workspaces;
  private final WorkspaceGenerationFilesystemSaga filesystemSaga;
  private final WorkspaceIgnoredArtifactGuard ignoredArtifacts;
  private final ObjectMapper objectMapper;
  private final PlatformTransactionManager transactionManager;

  public void rollback(
      UUID attemptId,
      UUID workspaceToken,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      Runnable authorityFence,
      String failureCode,
      String failureReason) {
    WorkspaceGenerationAttempt attempt = require(attemptId);
    CorrectionWorkspaceTransaction.DiffEvidence diff =
        workspaces.diffEvidence(checkpoint, authorityFence);
    String diffJson =
        json(
            Map.of(
                "changedFiles", diff.changedFiles(),
                "unifiedPatch", diff.unifiedPatch(),
                "patchCapturedBytes", diff.patchCapturedBytes(),
                "patchFullBytes", diff.patchFullBytes(),
                "patchTruncated", diff.patchTruncated(),
                "patchRedacted", diff.patchRedacted()));
    TransactionTemplate transactions = independentTransactions();
    requireCas(
        transactions.execute(
            ignored ->
                attempts.beginRollback(
                    attemptId,
                    workspaceToken,
                    attempt.getState(),
                    WorkspaceGenerationState.ROLLING_BACK,
                    diff.diffDigest(),
                    diffJson,
                    requireText(failureCode, "WORKSPACE_GENERATION_FAILURE_CODE_INVALID"),
                    bounded(failureReason),
                    Instant.now())),
        "WORKSPACE_GENERATION_ROLLBACK_CAS_FAILED");
    try {
      filesystemSaga.restoreRepositories(attemptId, checkpoint, authorityFence);
      ignoredArtifacts.cleanAttemptArtifacts(
          checkpoint,
          attempt.getIgnoredArtifactBaselineJson(),
          attempt.getIgnoredArtifactBaselineDigest(),
          authorityFence);
      assertRestored(attempt, checkpoint, authorityFence);
      requireCas(
          transactions.execute(
              ignored ->
                  attempts.markRolledBack(
                      attemptId,
                      workspaceToken,
                      WorkspaceGenerationState.ROLLING_BACK,
                      WorkspaceGenerationState.ROLLED_BACK,
                      Instant.now())),
          "WORKSPACE_GENERATION_ROLLED_BACK_CAS_FAILED");
    } catch (RuntimeException failure) {
      try {
        requireCas(
            transactions.execute(
                ignored ->
                    attempts.markRollbackFailed(
                        attemptId,
                        workspaceToken,
                        "ROLLBACK_PROOF_FAILED",
                        bounded(failure.getMessage()),
                        WorkspaceGenerationState.ROLLING_BACK,
                        WorkspaceGenerationState.RECOVERY_FAILED,
                        Instant.now())),
            "WORKSPACE_GENERATION_ROLLBACK_FAILURE_CAS_FAILED");
      } catch (RuntimeException persistenceFailure) {
        failure.addSuppressed(persistenceFailure);
      }
      throw failure;
    }
  }

  private void assertRestored(
      WorkspaceGenerationAttempt attempt,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      Runnable authorityFence) {
    String restored = workspaces.currentGeneration(checkpoint, authorityFence);
    if (attempt.getBeforeGeneration().equals(restored)) return;
    var residual = workspaces.diffEvidence(checkpoint, authorityFence);
    throw new IllegalStateException(
        "WORKSPACE_GENERATION_ROLLBACK_UNPROVEN:expected="
            + attempt.getBeforeGeneration()
            + ",actual="
            + restored
            + ",residualPaths="
            + residual.changedFiles());
  }

  private WorkspaceGenerationAttempt require(UUID attemptId) {
    return attempts
        .findById(attemptId)
        .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_ATTEMPT_NOT_FOUND"));
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_JSON_WRITE_FAILED", failure);
    }
  }

  private static String requireText(String value, String code) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    return value;
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "unspecified";
    return value.length() <= 4000 ? value : value.substring(0, 4000);
  }

  private static void requireCas(Integer updated, String code) {
    if (updated == null || updated != 1) throw new IllegalStateException(code);
  }

  private TransactionTemplate independentTransactions() {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
  }
}
