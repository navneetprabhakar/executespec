package com.navneet.executespec.run.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationState;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceipt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceiptRepository;

import org.junit.jupiter.api.Test;

class RunTerminalOutcomeProjectionServiceTest {

  @Test
  void environmentUnavailableOverridesGenericProductFailureAcrossSharedTerminalContract() {
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .state(WorkspaceGenerationState.ROLLED_BACK)
            .candidateGeneration("a".repeat(64))
            .failureCode("TASK_VERIFICATION_REJECTED")
            .build();
    WorkspaceGenerationVerificationReceipt receipt =
        WorkspaceGenerationVerificationReceipt.builder()
            .attemptId(attemptId)
            .outcome("UNAVAILABLE")
            .failureCategory("ENVIRONMENT")
            .environmentCode("BUILD_POLICY_DENIED")
            .build();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc("run-1")).thenReturn(List.of(attempt));
    when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId))
        .thenReturn(Optional.of(receipt));

    RunTerminalOutcome outcome =
        new RunTerminalOutcomeProjectionService(attempts, receipts)
            .project(
                PipelineRun.builder()
                    .runId("run-1")
                    .status("FAILED")
                    .statusReason("ALL_TASKS_FAILED")
                    .failureReason("build failed")
                    .build());

    assertThat(outcome.code())
        .isEqualTo("WORKSPACE_GENERATION_VERIFICATION_ENVIRONMENT_UNAVAILABLE");
    assertThat(outcome.customerTitle()).isEqualTo("Verification environment unavailable");
    assertThat(outcome.customerMessage()).contains("not a product-code failure");
    assertThat(outcome.nextAction().type()).isEqualTo("RETRY_RUN");
  }

  @Test
  void newestAcceptedAttemptPreventsAnOlderRollbackFromOverridingTheRunOutcome() {
    WorkspaceGenerationAttempt accepted =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .state(WorkspaceGenerationState.ACCEPTED)
            .candidateGeneration("b".repeat(64))
            .build();
    WorkspaceGenerationAttempt obsoleteRollback =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .state(WorkspaceGenerationState.ROLLED_BACK)
            .candidateGeneration("a".repeat(64))
            .failureCode("TASK_VERIFICATION_REJECTED")
            .build();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc("run-latest"))
        .thenReturn(List.of(accepted, obsoleteRollback));
    when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(accepted.getId()))
        .thenReturn(Optional.empty());

    RunTerminalOutcome outcome =
        new RunTerminalOutcomeProjectionService(attempts, receipts)
            .project(
                PipelineRun.builder()
                    .runId("run-latest")
                    .status("FAILED")
                    .statusReason("FINAL_BUILD_FAILED")
                    .build());

    assertThat(outcome.code()).isEqualTo("FINAL_BUILD_FAILED");
    assertThat(outcome.customerTitle()).doesNotContain("rejected safely");
  }

  @Test
  void evidenceLookupFailureDegradesToTheExistingTerminalContract() {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc("run-fallback"))
        .thenThrow(new IllegalStateException("database unavailable"));

    RunTerminalOutcome outcome =
        new RunTerminalOutcomeProjectionService(attempts, receipts)
            .project(
                PipelineRun.builder()
                    .runId("run-fallback")
                    .status("FAILED")
                    .statusReason("FINAL_BUILD_FAILED")
                    .build());

    assertThat(outcome.code()).isEqualTo("FINAL_BUILD_FAILED");
  }
}
