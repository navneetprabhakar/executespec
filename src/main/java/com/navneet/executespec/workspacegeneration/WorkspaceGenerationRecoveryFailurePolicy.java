package com.navneet.executespec.workspacegeneration;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

/** Persists the bounded retry or exhausted-quarantine outcome of a failed recovery claim. */
final class WorkspaceGenerationRecoveryFailurePolicy {

  static final int MAX_ATTEMPTS = 3;
  private static final Duration BACKOFF_BASE = Duration.ofSeconds(30);
  private static final String FAILURE_CODE = "ROLLBACK_PROOF_FAILED";

  private final WorkspaceGenerationAttemptRepository attempts;

  WorkspaceGenerationRecoveryFailurePolicy(WorkspaceGenerationAttemptRepository attempts) {
    this.attempts = attempts;
  }

  FailureDisposition persist(
      UUID attemptId,
      UUID recoveryOwnerToken,
      RuntimeException failure,
      TransactionTemplate transactions) {
    WorkspaceGenerationAttempt attempt =
        attempts
            .findById(attemptId)
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_ATTEMPT_NOT_FOUND"));
    Instant failedAt = Instant.now();
    boolean exhausted =
        attempt.getRecoveryAttemptCount() != null
            && attempt.getRecoveryAttemptCount() >= MAX_ATTEMPTS;
    Integer persisted;
    if (exhausted) {
      persisted =
          transactions.execute(
              ignored ->
                  attempts.markRecoveryExhausted(
                      attemptId,
                      recoveryOwnerToken,
                      FAILURE_CODE,
                      bounded(failure.getMessage()),
                      WorkspaceGenerationState.ROLLING_BACK,
                      WorkspaceGenerationState.RECOVERY_FAILED,
                      MAX_ATTEMPTS,
                      failedAt));
    } else {
      Instant nextAttempt = failedAt.plus(backoff(attempt.getRecoveryAttemptCount()));
      persisted =
          transactions.execute(
              ignored ->
                  attempts.markRecoveryRetry(
                      attemptId,
                      recoveryOwnerToken,
                      FAILURE_CODE,
                      bounded(failure.getMessage()),
                      WorkspaceGenerationState.ROLLING_BACK,
                      WorkspaceGenerationState.RECOVERY_FAILED,
                      MAX_ATTEMPTS,
                      nextAttempt,
                      failedAt));
    }
    requireCas(persisted);
    return new FailureDisposition(exhausted, failedAt);
  }

  private static Duration backoff(Integer attemptCount) {
    int boundedCount = Math.max(1, Math.min(MAX_ATTEMPTS, attemptCount == null ? 1 : attemptCount));
    return BACKOFF_BASE.multipliedBy(1L << (boundedCount - 1));
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "unspecified";
    return value.length() <= 4000 ? value : value.substring(0, 4000);
  }

  private static void requireCas(Integer value) {
    if (value == null || value != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_RECOVERY_FAILURE_CAS_FAILED");
    }
  }

  record FailureDisposition(boolean exhausted, Instant failedAt) {}
}
