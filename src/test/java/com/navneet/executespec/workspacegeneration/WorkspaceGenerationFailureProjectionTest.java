package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationFailureProjectionTest {

  @Test
  void projectsEveryCustomerSafeFailureCategoryFromDurableFieldsOnly() {
    assertCategory(
        attempt(WorkspaceGenerationState.ROLLED_BACK, true, "TASK_VERIFICATION_REJECTED", null),
        receipt("FAILED", "PRODUCT"),
        WorkspaceGenerationFailureProjection.Category.ROLLBACK_PROVEN);
    assertCategory(
        attempt(
            WorkspaceGenerationState.RECOVERY_FAILED,
            true,
            "TASK_VERIFICATION_REJECTED",
            "ROLLBACK_PROOF_FAILED"),
        receipt("FAILED", "PRODUCT"),
        WorkspaceGenerationFailureProjection.Category.ROLLBACK_UNPROVEN);
    assertCategory(
        attempt(WorkspaceGenerationState.ADMITTED, false, "PROVIDER_DISPATCH_FAILED", null),
        null,
        WorkspaceGenerationFailureProjection.Category.NO_CANDIDATE);
    assertCategory(
        attempt(WorkspaceGenerationState.ROLLED_BACK, true, "TASK_VERIFICATION_REJECTED", null),
        receipt("UNAVAILABLE", "ENVIRONMENT"),
        WorkspaceGenerationFailureProjection.Category.VERIFICATION_ENVIRONMENT_UNAVAILABLE);
    assertCategory(
        attempt(
            WorkspaceGenerationState.ROLLED_BACK, true, "CHECKPOINT_UPLOAD_UNACKNOWLEDGED", null),
        null,
        WorkspaceGenerationFailureProjection.Category.CHECKPOINT_FAILED);
    assertCategory(
        attempt(WorkspaceGenerationState.VERIFYING, true, "FRONTIER_CAS_CONFLICT", null),
        null,
        WorkspaceGenerationFailureProjection.Category.FRONTIER_CONFLICT);
    assertCategory(
        attempt(WorkspaceGenerationState.VERIFYING, true, "TERMINAL_OPERATION_CONFLICT", null),
        null,
        WorkspaceGenerationFailureProjection.Category.INTERNAL_PROTOCOL_CONFLICT);
  }

  private static void assertCategory(
      WorkspaceGenerationAttempt attempt,
      WorkspaceGenerationVerificationReceipt receipt,
      WorkspaceGenerationFailureProjection.Category expected) {
    WorkspaceGenerationFailureProjection projection =
        WorkspaceGenerationFailureProjection.project(attempt, receipt);
    assertThat(projection).isNotNull();
    assertThat(projection.category()).isEqualTo(expected);
    assertThat(projection.publicationBlocked()).isTrue();
    assertThat(projection.candidatePresent()).isEqualTo(attempt.getCandidateGeneration() != null);
  }

  private static WorkspaceGenerationAttempt attempt(
      WorkspaceGenerationState state,
      boolean candidate,
      String failureCode,
      String recoveryFailureCode) {
    return WorkspaceGenerationAttempt.builder()
        .id(UUID.randomUUID())
        .state(state)
        .candidateGeneration(candidate ? "a".repeat(64) : null)
        .failureCode(failureCode)
        .recoveryFailureCode(recoveryFailureCode)
        .build();
  }

  private static WorkspaceGenerationVerificationReceipt receipt(
      String outcome, String failureCategory) {
    return WorkspaceGenerationVerificationReceipt.builder()
        .outcome(outcome)
        .failureCategory(failureCategory)
        .build();
  }
}
