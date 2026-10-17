package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;

import org.junit.jupiter.api.Test;

class BuildAcceptanceDecisionsTest {

  @Test
  void rejectionOverridesEarlierDoneAndRequestsOnlyOneBoundedRepair() {
    TaskOutcome rejected =
        BuildTaskAcceptanceOutcomes.rejected(provisionalDone(), "required behavior failed");
    assertThat(rejected.smokeTestPassed()).isFalse();
    assertThat(rejected.goalDonenessDecision().state()).isEqualTo(GoalDonenessState.FAILED);
    assertThat(rejected.goalDonenessDecision().recommendedDecision())
        .isEqualTo(RunContinuationDecision.STOP_FAILED);
    assertThat(
            BuildContinuationOutcomes.sameProviderRetryRequired(
                rejected.continuationDecision(), rejected))
        .isTrue();
  }

  @Test
  void infrastructureFailureCannotTriggerProviderEscalationOrCodingRetry() {
    WorkspaceGenerationLifecycle.Verification unavailable =
        new WorkspaceGenerationLifecycle.Verification(
            "BUILD_ONLY",
            "{}",
            "UNAVAILABLE",
            "ENVIRONMENT",
            "RUNTIME_UNAVAILABLE",
            "DISPOSABLE_EXACT_GENERATION",
            false,
            false,
            0,
            0,
            0,
            0,
            "{}",
            "TEST",
            "BUILD",
            java.time.Instant.now(),
            java.time.Instant.now());
    TaskOutcome rejected =
        BuildTaskAcceptanceOutcomes.rejected(provisionalDone(), "runtime missing", unavailable);
    assertThat(rejected.goalDonenessDecision().state()).isEqualTo(GoalDonenessState.BLOCKED);
    assertThat(rejected.continuationDecision().decision())
        .isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(rejected.continuationDecision().selectedCandidate()).isEmpty();
    assertThat(
            BuildContinuationOutcomes.sameProviderRetryRequired(
                rejected.continuationDecision(), rejected))
        .isFalse();
  }

  @Test
  void retryGetsConcreteFinalFailureWithoutExposingPrivateVerifierOutputToClients()
      throws Exception {
    var rejected =
        BuildTaskAcceptanceOutcomes.rejected(
            provisionalDone(),
            "final checks failed",
            new WorkspaceGenerationLifecycle.Verification(
                "BUILD_AND_TEST",
                "{}",
                "FAILED",
                "PRODUCT",
                null,
                "DISPOSABLE_EXACT_GENERATION",
                true,
                true,
                1,
                1,
                0,
                0,
                "{\"testOutput\":\"AssertionError: private-verifier-detail\"}",
                "TEST",
                "BUILD",
                java.time.Instant.now(),
                java.time.Instant.now()));
    assertThat(rejected.acceptanceDiagnostics()).contains("private-verifier-detail");
    assertThat(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(rejected))
        .doesNotContain("private-verifier-detail", "acceptanceDiagnostics");
  }

  @Test
  void acceptedReceiptReplacesProvisionalDecision() {
    TaskOutcome accepted =
        BuildTaskAcceptanceOutcomes.accepted(
            PlannedTask.builder().id("T1").title("Feature").build(), provisionalDone());
    assertThat(accepted.goalDonenessDecision().evidence())
        .contains("authority=FROZEN_TASK_ACCEPTANCE");
    assertThat(accepted.continuationDecision().decision())
        .isEqualTo(RunContinuationDecision.STOP_DONE);
  }

  private static TaskOutcome provisionalDone() {
    return TaskOutcome.builder()
        .taskId("T1")
        .taskTitle("Feature")
        .status(TaskOutcome.Status.SUCCESS)
        .smokeTestPassed(true)
        .goalDonenessDecision(
            new GoalDonenessDecision(
                GoalDonenessState.DONE,
                RunContinuationDecision.STOP_DONE,
                "smoke passed",
                "implement feature",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false))
        .build();
  }
}
