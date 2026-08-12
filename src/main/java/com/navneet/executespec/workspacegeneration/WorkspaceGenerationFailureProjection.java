package com.navneet.executespec.workspacegeneration;

import java.util.Locale;

/** Customer-safe deterministic projection of one failed BUILD generation attempt. */
public record WorkspaceGenerationFailureProjection(
    Category category,
    Stage stage,
    boolean retryable,
    boolean candidatePresent,
    boolean rollbackProven,
    boolean publicationBlocked,
    RecommendedAction recommendedAction) {

  public enum Category {
    NO_CANDIDATE,
    VERIFICATION_REJECTED,
    VERIFICATION_ENVIRONMENT_UNAVAILABLE,
    CHECKPOINT_FAILED,
    FRONTIER_CONFLICT,
    ROLLBACK_PROVEN,
    ROLLBACK_UNPROVEN,
    AUTHORITY_LOST,
    INTERNAL_PROTOCOL_CONFLICT
  }

  public enum Stage {
    ADMISSION,
    GENERATION,
    VERIFICATION,
    CHECKPOINT,
    FRONTIER,
    ROLLBACK,
    INTERNAL_PROTOCOL
  }

  public enum RecommendedAction {
    RETRY_GENERATION,
    REVIEW_FAILED_CHECKS,
    RETRY_AFTER_ENVIRONMENT_RECOVERY,
    WAIT_FOR_RECONCILIATION,
    CONTACT_SUPPORT
  }

  public static WorkspaceGenerationFailureProjection project(
      WorkspaceGenerationAttempt attempt, WorkspaceGenerationVerificationReceipt receipt) {
    if (attempt == null) return null;
    boolean candidate =
        hasText(attempt.getCandidateGeneration()) || hasText(attempt.getRejectedDiffDigest());
    boolean rolledBack = attempt.getState() == WorkspaceGenerationState.ROLLED_BACK;
    String code = normalizedCode(attempt);

    if (contains(code, "AUTHORITY")) {
      return failure(
          Category.AUTHORITY_LOST,
          Stage.GENERATION,
          false,
          candidate,
          rolledBack,
          RecommendedAction.WAIT_FOR_RECONCILIATION);
    }
    if (contains(code, "FRONTIER") || contains(code, "CAS_CONFLICT")) {
      return failure(
          Category.FRONTIER_CONFLICT,
          Stage.FRONTIER,
          false,
          candidate,
          rolledBack,
          RecommendedAction.WAIT_FOR_RECONCILIATION);
    }
    if (contains(code, "CHECKPOINT")) {
      return failure(
          Category.CHECKPOINT_FAILED,
          Stage.CHECKPOINT,
          rolledBack,
          candidate,
          rolledBack,
          rolledBack ? RecommendedAction.RETRY_GENERATION : RecommendedAction.CONTACT_SUPPORT);
    }
    if (receipt != null && "ENVIRONMENT".equals(receipt.getFailureCategory())) {
      return failure(
          Category.VERIFICATION_ENVIRONMENT_UNAVAILABLE,
          Stage.VERIFICATION,
          true,
          candidate,
          rolledBack,
          RecommendedAction.RETRY_AFTER_ENVIRONMENT_RECOVERY);
    }
    if (attempt.getState() == WorkspaceGenerationState.RECOVERY_FAILED) {
      return failure(
          Category.ROLLBACK_UNPROVEN,
          Stage.ROLLBACK,
          false,
          candidate,
          false,
          RecommendedAction.CONTACT_SUPPORT);
    }
    if (rolledBack) {
      return failure(
          Category.ROLLBACK_PROVEN,
          receipt == null ? Stage.GENERATION : Stage.VERIFICATION,
          true,
          candidate,
          true,
          receipt == null
              ? RecommendedAction.RETRY_GENERATION
              : RecommendedAction.REVIEW_FAILED_CHECKS);
    }
    if (receipt != null && "FAILED".equals(receipt.getOutcome())) {
      return failure(
          Category.VERIFICATION_REJECTED,
          Stage.VERIFICATION,
          false,
          candidate,
          false,
          RecommendedAction.WAIT_FOR_RECONCILIATION);
    }
    if (!candidate && hasText(code)) {
      return failure(
          Category.NO_CANDIDATE,
          Stage.GENERATION,
          true,
          false,
          false,
          RecommendedAction.RETRY_GENERATION);
    }
    if (hasText(code)) {
      return failure(
          Category.INTERNAL_PROTOCOL_CONFLICT,
          Stage.INTERNAL_PROTOCOL,
          false,
          candidate,
          false,
          RecommendedAction.CONTACT_SUPPORT);
    }
    return null;
  }

  private static WorkspaceGenerationFailureProjection failure(
      Category category,
      Stage stage,
      boolean retryable,
      boolean candidatePresent,
      boolean rollbackProven,
      RecommendedAction action) {
    return new WorkspaceGenerationFailureProjection(
        category, stage, retryable, candidatePresent, rollbackProven, true, action);
  }

  private static String normalizedCode(WorkspaceGenerationAttempt attempt) {
    String code =
        hasText(attempt.getRecoveryFailureCode())
            ? attempt.getRecoveryFailureCode()
            : attempt.getFailureCode();
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean contains(String value, String token) {
    return value != null && value.contains(token);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
