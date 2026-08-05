package com.navneet.executespec.run.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RunTerminalOutcomeContractTest {

  @Test
  void budgetStopIsActionableAndDoesNotClaimThereWasNoSpend() {
    RunTerminalOutcome outcome =
        project("FAILED", "WORKER_BUDGET_EXCEEDED", "$provider stack", 0.42, 0.50, null);

    assertThat(outcome.code()).isEqualTo("WORKER_BUDGET_EXCEEDED");
    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.ACTION_REQUIRED);
    assertThat(outcome.customerMessage()).contains("cost limit", "No pull request");
    assertThat(outcome.nextAction().label()).contains("higher cost cap");
    assertThat(outcome.impact().actualCostUsd()).isEqualTo(0.42);
    assertThat(outcome.impact().capUsd()).isEqualTo(0.50);
    assertThat(outcome.impact().publication())
        .isEqualTo(RunTerminalOutcome.Publication.NOT_CREATED);
    assertThat(outcome.supportCode()).isNull();
    assertThat(outcome.automationExitCode()).isEqualTo(3);
  }

  @Test
  void plannerPolicyBlockReportsNoCodeOrPublicationAndIsNotRetryableUnchanged() {
    RunTerminalOutcome outcome =
        project("FAILED", "PLANNER_RECOVERY_POLICY_BLOCKED", null, 0.03, 1.0, null);

    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.POLICY_BLOCKED);
    assertThat(outcome.customerMessage()).contains("No code or pull request was created");
    assertThat(outcome.retryable()).isFalse();
    assertThat(outcome.impact().codeChanged()).isFalse();
  }

  @Test
  void cancellationIsNeutralAndNotAPlatformFailure() {
    RunTerminalOutcome outcome = project("CANCELLED", null, "Cancelled by user", 0.01, 1.0, null);

    assertThat(outcome.code()).isEqualTo("RUN_CANCELLED");
    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.USER_STOPPED);
    assertThat(outcome.customerTitle()).isEqualTo("Run cancelled");
    assertThat(outcome.supportCode()).isNull();
  }

  @Test
  void validationFailurePointsToEvidenceWithoutLeakingRawFailure() {
    RunTerminalOutcome outcome =
        project(
            "PARTIAL", "VALIDATION_REPORT_LOW_QUALITY", "secret provider payload", 0.70, 1.0, null);

    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.ACTION_REQUIRED);
    assertThat(outcome.customerMessage()).contains("validation threshold");
    assertThat(outcome.customerMessage()).doesNotContain("secret provider payload");
  }

  @ParameterizedTest
  @CsvSource({
    "DETERMINISTIC_HTTP_FORBIDDEN_METHOD_PRESENT,exposed an HTTP method",
    "DETERMINISTIC_HTTP_METHOD_REJECTION_UNPROVEN,could not prove the required HTTP 405"
  })
  void methodRejectionFailuresHaveStableCustomerSemantics(String code, String message) {
    RunTerminalOutcome outcome = project("FAILED", code, "raw source detail", 0.02, 1.0, null);

    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.ACTION_REQUIRED);
    assertThat(outcome.customerMessage()).contains(message).doesNotContain("raw source detail");
  }

  @Test
  void unknownFailureIsLeakSafeAndCarriesStableSupportCode() {
    RunTerminalOutcome outcome =
        project("FAILED", null, "java.sql.SQLException: password=secret", 0.25, 1.0, null);

    assertThat(outcome.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.PLATFORM_FAILURE);
    assertThat(outcome.customerMessage()).doesNotContain("SQLException", "secret");
    assertThat(outcome.supportCode()).isEqualTo("ES-RUN-123E4567E89B12D3A456426614174000");
    assertThat(outcome.automationExitCode()).isEqualTo(1);
  }

  @Test
  void unknownStatusReasonCannotBecomeAPublicCodeOrLeakASecret() {
    RunTerminalOutcome outcome =
        project("FAILED", "provider stack api_key=secret", null, 0.25, 1.0, null);

    assertThat(outcome.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(outcome.customerMessage()).doesNotContain("provider", "api_key", "secret");
  }

  @ParameterizedTest
  @CsvSource({"CLEAN_COMPLETION", "CLEAN_WITH_INTERMEDIATE_WARNINGS", "PATCH_STAGNATION"})
  void failedStatusCannotBePromotedByAContradictorySuccessOrWarningReason(String reason) {
    RunTerminalOutcome outcome = project("FAILED", reason, null, 0.25, 1.0, null);

    assertThat(outcome.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.PLATFORM_FAILURE);
    assertThat(outcome.automationExitCode()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({
    "COMPLETE,PATCH_STAGNATION,WARNING",
    "COMPLETE,PATCH_STOPPED_WITHOUT_COMPLETE,WARNING",
    "PARTIAL,RE_REVIEW_FAILED,ACTION_REQUIRED",
    "PARTIAL,RE_REVIEW_NO_WORKSPACE,ACTION_REQUIRED",
    "ABORTED,,USER_STOPPED",
    "REJECTED,,USER_STOPPED"
  })
  void emittedTerminalReasonsHaveExplicitCustomerSemantics(
      String status, String reason, RunTerminalOutcome.Category category) {
    RunTerminalOutcome outcome = project(status, reason, null, 0.25, 1.0, null);

    assertThat(outcome).isNotNull();
    assertThat(outcome.category()).isEqualTo(category);
    assertThat(outcome.code()).doesNotContain(" ", "SECRET", "EXCEPTION");
  }

  @Test
  void budgetStopWithExistingPullRequestDoesNotClaimNoPullRequestExists() {
    RunTerminalOutcome outcome =
        project(
            "FAILED",
            "WORKER_BUDGET_EXCEEDED",
            null,
            0.42,
            0.50,
            "https://github.com/example/repo/pull/7");

    assertThat(outcome.impact().publication()).isEqualTo(RunTerminalOutcome.Publication.PR_CREATED);
    assertThat(outcome.impact().prUrl()).isEqualTo("https://github.com/example/repo/pull/7");
    assertThat(outcome.impact().codeChanged())
        .as("an existing PR is not attempt-scoped mutation proof")
        .isNull();
    assertThat(outcome.customerMessage())
        .contains("existing pull request", "not updated by this attempt")
        .doesNotContain("No pull request was published");
  }

  @ParameterizedTest
  @CsvSource({
    "BYOK_CONTEXT_UNAVAILABLE",
    "PROJECT_PROVIDER_NOT_CONFIGURED",
    "PROJECT_PROVIDER_INVALID",
    "MODEL_MATRIX_UNAVAILABLE"
  })
  void unavailableAiRoutesPointToProviderSettingsInsteadOfRetryingUnchanged(String code) {
    RunTerminalOutcome outcome = project("FAILED", code, "provider detail", 0.0, 1.0, null);

    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.ACTION_REQUIRED);
    assertThat(outcome.retryable()).isTrue();
    assertThat(outcome.impact().codeChanged()).isFalse();
    assertThat(outcome.customerMessage()).doesNotContain("provider detail");
    assertThat(outcome.nextAction().type()).isEqualTo("OPEN_PROVIDER_SETTINGS");
    assertThat(outcome.nextAction().label())
        .isEqualTo("Open provider settings and correct the route.");
    assertThat(outcome.nextAction().href()).isEqualTo("/provider-keys");
  }

  @Test
  void completedPublishedRunCanClaimCodeChanged() {
    RunTerminalOutcome outcome =
        project(
            "COMPLETE",
            "CLEAN_COMPLETION",
            null,
            0.20,
            1.0,
            "https://github.com/example/repo/pull/8");

    assertThat(outcome.impact().codeChanged()).isTrue();
  }

  @Test
  void cleanRunWithoutPublicationStillClaimsCodeChangedFromItsOwnValidatedDiff() {
    RunTerminalOutcome outcome = project("COMPLETE", "CLEAN_COMPLETION", null, 0.20, 1.0, null);

    assertThat(outcome.impact().publication())
        .isEqualTo(RunTerminalOutcome.Publication.NOT_CREATED);
    assertThat(outcome.impact().codeChanged()).isTrue();
  }

  @Test
  void cleanRunWhoseExpectedPublicationWasBlockedIsHonestAndPointsAtPublicationEvidence() {
    RunTerminalOutcome outcome =
        projectExpectedPublication(
            "COMPLETE",
            "CLEAN_COMPLETION",
            "PUBLICATION_BLOCKED[PUBLICATION_EVIDENCE_STALE]: gate=EVIDENCE_FRESHNESS; Regenerate"
                + " validation on the current workspace.",
            null);

    assertThat(outcome.code()).isEqualTo("CLEAN_COMPLETION");
    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.SUCCESS);
    assertThat(outcome.customerTitle()).contains("pull request not created");
    assertThat(outcome.customerMessage())
        .contains("completed successfully")
        .contains("pull request was not created")
        .contains("PUBLICATION_EVIDENCE_STALE");
    assertThat(outcome.nextAction().type()).isEqualTo("OPEN_PUBLICATION_ELIGIBILITY");
    assertThat(outcome.nextAction().label()).containsIgnoringCase("publication evidence");
    assertThat(outcome.impact().codeChanged()).isTrue();
    assertThat(outcome.automationExitCode()).isEqualTo(0);
  }

  @Test
  void cleanUnpublishedRunWithoutAMarkerStillStatesThePullRequestWasNotCreated() {
    RunTerminalOutcome outcome =
        projectExpectedPublication("COMPLETE", "CLEAN_COMPLETION", null, null);

    assertThat(outcome.customerMessage())
        .contains("pull request was not created")
        .doesNotContain("blocked by");
    assertThat(outcome.nextAction().type()).isEqualTo("OPEN_PUBLICATION_ELIGIBILITY");
  }

  @Test
  void cleanRunWithExpectedPublicationAndAPrKeepsThePlainSuccessProjection() {
    RunTerminalOutcome outcome =
        projectExpectedPublication(
            "COMPLETE", "CLEAN_COMPLETION", null, "https://github.com/example/repo/pull/9");

    assertThat(outcome.customerTitle()).isEqualTo("Run complete");
    assertThat(outcome.customerMessage()).isEqualTo("The run completed successfully.");
    assertThat(outcome.nextAction()).isNull();
  }

  @Test
  void cleanRunNotExpectedToPublishKeepsThePlainSuccessProjection() {
    RunTerminalOutcome outcome = project("COMPLETE", "CLEAN_COMPLETION", null, 0.20, 1.0, null);

    assertThat(outcome.customerTitle()).isEqualTo("Run complete");
    assertThat(outcome.customerMessage()).isEqualTo("The run completed successfully.");
    assertThat(outcome.nextAction()).isNull();
  }

  @Test
  void warningRunWhoseExpectedPublicationWasWithheldNamesTheMissingPullRequest() {
    RunTerminalOutcome outcome =
        projectExpectedPublication("PARTIAL", "CLEAN_WITH_INTERMEDIATE_WARNINGS", null, null);

    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.WARNING);
    assertThat(outcome.customerMessage()).contains("pull request was not created");
    assertThat(outcome.nextAction().type()).isEqualTo("OPEN_PUBLICATION_ELIGIBILITY");
    assertThat(outcome.impact().codeChanged()).isTrue();
  }

  @Test
  void localExecutionRunNeverClaimsAMissingServerPullRequest() {
    RunTerminalOutcome outcome =
        projectExpectedPublication("PARTIAL", "LOCAL_EXECUTION", null, null);

    assertThat(outcome.customerMessage()).doesNotContain("pull request was not created");
  }

  @Test
  void refundedFailureStatesTheCreditRefundInMessageAndImpact() {
    RunTerminalOutcome outcome =
        RunTerminalOutcomeContract.project(
            "123e4567-e89b-12d3-a456-426614174000",
            "FAILED",
            "FINAL_BUILD_FAILED",
            null,
            0.0,
            1.0,
            null,
            false,
            true);

    assertThat(outcome.impact().creditsRefunded()).isTrue();
    assertThat(outcome.customerMessage())
        .contains("The run credit charged for this run was refunded.");
  }

  @Test
  void unrefundedFailureCarriesAnExplicitFalseWithoutClaimingARefund() {
    RunTerminalOutcome outcome =
        RunTerminalOutcomeContract.project(
            "123e4567-e89b-12d3-a456-426614174000",
            "FAILED",
            "FINAL_BUILD_FAILED",
            null,
            0.0,
            1.0,
            null,
            false,
            false);

    assertThat(outcome.impact().creditsRefunded()).isFalse();
    assertThat(outcome.customerMessage()).doesNotContain("refunded");
  }

  @Test
  void legacyProjectionsLeaveRefundStateUnresolvedRatherThanFalse() {
    RunTerminalOutcome outcome = project("FAILED", "FINAL_BUILD_FAILED", null, 0.0, 1.0, null);

    assertThat(outcome.impact().creditsRefunded()).isNull();
    assertThat(outcome.customerMessage()).doesNotContain("refunded");
  }

  @Test
  void inFlightRunHasNoTerminalProjection() {
    assertThat(project("WORKING", null, null, 0.1, 1.0, null)).isNull();
  }

  @Test
  void branchMismatchPreflightGetsHonestBranchOutcomeNotAccessAdvice() {
    RunTerminalOutcome outcome =
        project(
            "FAILED",
            null,
            "WORKSPACE_PREFLIGHT_FAILED:BRANCH_MISMATCH: repository branch mismatch for acme/api:"
                + " expected main but workspace is on executespec/deadbeef",
            0.0,
            5.0,
            null);

    assertThat(outcome.code()).isEqualTo("WORKSPACE_PREFLIGHT_FAILED");
    assertThat(outcome.customerTitle()).isEqualTo("Workspace branch mismatch");
    assertThat(outcome.customerMessage()).contains("not on the expected repository branch");
    assertThat(outcome.nextAction().label()).doesNotContainIgnoringCase("repository access");
  }

  @Test
  void genericWorkspacePreflightFailureKeepsAccessAdvice() {
    RunTerminalOutcome outcome =
        project(
            "FAILED",
            null,
            "WORKSPACE_PREFLIGHT_FAILED:CLONE_MISSING: clone missing for acme/api",
            0.0,
            5.0,
            null);

    assertThat(outcome.customerTitle()).isEqualTo("Repository workspace unavailable");
    assertThat(outcome.nextAction().label()).containsIgnoringCase("repository access");
  }

  @Test
  void autoPublicationExpectedRequiresARepoBackedServerRunWithoutWithheldConsent() {
    com.navneet.executespec.state.entity.PipelineRun run =
        new com.navneet.executespec.state.entity.PipelineRun();
    run.setRepoUrl("https://github.com/example/repo.git");
    assertThat(RunTerminalOutcomeContract.autoPublicationExpected(run)).isTrue();

    run.setSkipAutoPush(true);
    assertThat(RunTerminalOutcomeContract.autoPublicationExpected(run)).isFalse();

    run.setSkipAutoPush(false);
    run.setLocalExecutionRequested(true);
    assertThat(RunTerminalOutcomeContract.autoPublicationExpected(run)).isFalse();

    com.navneet.executespec.state.entity.PipelineRun repoless =
        new com.navneet.executespec.state.entity.PipelineRun();
    assertThat(RunTerminalOutcomeContract.autoPublicationExpected(repoless)).isFalse();
  }

  private static RunTerminalOutcome project(
      String status,
      String statusReason,
      String failureReason,
      Double actualCost,
      Double hardCap,
      String prUrl) {
    return RunTerminalOutcomeContract.project(
        "123e4567-e89b-12d3-a456-426614174000",
        status,
        statusReason,
        failureReason,
        actualCost,
        hardCap,
        prUrl);
  }

  private static RunTerminalOutcome projectExpectedPublication(
      String status, String statusReason, String failureReason, String prUrl) {
    return RunTerminalOutcomeContract.project(
        "123e4567-e89b-12d3-a456-426614174000",
        status,
        statusReason,
        failureReason,
        0.20,
        1.0,
        prUrl,
        true);
  }
}
