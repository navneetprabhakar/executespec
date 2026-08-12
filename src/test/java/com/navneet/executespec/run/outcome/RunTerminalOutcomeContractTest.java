package com.navneet.executespec.run.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.workspacegeneration.WorkspaceGenerationFailureProjection;

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
  void routePinnedNamesThePinAndTheUnpinActionInsteadOfClaimingRecoveryRan() {
    // Dev run 322919af. The pin forbade the escalation that IS the recovery, so the old copy —
    // "bounded recovery could not produce an approvable plan" — described work never attempted.
    RunTerminalOutcome outcome =
        project(
            "FAILED",
            "PLANNER_RECOVERY_POLICY_BLOCKED",
            "ROUTE_PINNED: planner recovery policy blocked: ROUTE_PINNED (exact run route forbids"
                + " escalation)",
            0.81,
            null,
            null);

    assertThat(outcome.customerTitle()).contains("exact model pin");
    assertThat(outcome.customerMessage()).contains("pinned an exact model", "forbids switching");
    assertThat(outcome.customerMessage()).doesNotContain("bounded recovery");
    assertThat(outcome.nextAction().label()).contains("without the exact model pin");
    // Unpinning makes this run retryable; reporting it as terminal was the second false claim.
    assertThat(outcome.retryable()).isTrue();
    assertThat(outcome.code()).isEqualTo("PLANNER_RECOVERY_POLICY_BLOCKED");
    assertThat(outcome.impact().codeChanged()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "CONFIRMATION_REQUIRED,Confirm model upgrades",
    "BENCHMARK_EVIDENCE_REQUIRED,Require benchmark evidence",
  })
  void policyLeversNameTheExactSettingTheCustomerCanChange(String blockCode, String setting) {
    RunTerminalOutcome outcome =
        project(
            "FAILED", "PLANNER_RECOVERY_POLICY_BLOCKED", blockCode + ": blocked", 0.4, null, null);

    assertThat(outcome.nextAction().label()).contains(setting);
    assertThat(outcome.retryable()).isTrue();
  }

  @Test
  void genuineExhaustionKeepsTheOriginalNonRetryablePolicyWording() {
    RunTerminalOutcome outcome =
        project(
            "FAILED",
            "PLANNER_RECOVERY_POLICY_BLOCKED",
            "NO_QUALITY_STEP_UP: bounded planner escalation was exhausted",
            0.4,
            null,
            null);

    assertThat(outcome.customerMessage()).contains("failed deterministic validation");
    assertThat(outcome.retryable()).isFalse();
  }

  @Test
  void anUnrecognisedBlockTokenIsNotTreatedAsProofThatALeverExists() {
    // A future or malformed token must not invent an "unpin your model" instruction.
    RunTerminalOutcome outcome =
        project(
            "FAILED",
            "PLANNER_RECOVERY_POLICY_BLOCKED",
            "SOMETHING_NEW: unrecognised",
            0.4,
            null,
            null);

    assertThat(outcome.retryable()).isFalse();
    assertThat(outcome.customerMessage()).contains("failed deterministic validation");
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
  void workspaceRecoveryFailureIsSpecificLeakSafeAndPublicationBlocked() {
    RunTerminalOutcome outcome =
        project(
            "FAILED",
            "WORKSPACE_GENERATION_RECOVERY_FAILED",
            "WORKSPACE_IGNORED_ARTIFACT_CLASSIFICATION_CHANGED:/secret/path",
            0.25,
            5.0,
            null);

    assertThat(outcome.code()).isEqualTo("WORKSPACE_GENERATION_RECOVERY_FAILED");
    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.PLATFORM_FAILURE);
    assertThat(outcome.customerMessage())
        .contains("could not prove that the workspace was restored")
        .contains("Publication was blocked")
        .doesNotContain("IGNORED_ARTIFACT", "/secret/path");
    assertThat(outcome.supportCode()).isNotBlank();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "ROLLBACK_PROVEN|Generated code was rejected"
            + " safely|REVIEW_EVIDENCE|REVIEW_FAILED_CHECKS|failed deterministic checks",
        "ROLLBACK_UNPROVEN|Workspace recovery is unresolved|CONTACT_SUPPORT|CONTACT_SUPPORT|contact"
            + " support",
        "NO_CANDIDATE|Generation did not produce a candidate|RETRY_RUN|RETRY_GENERATION|Retry"
            + " generation",
        "VERIFICATION_ENVIRONMENT_UNAVAILABLE|Verification environment"
            + " unavailable|RETRY_RUN|RETRY_AFTER_ENVIRONMENT_RECOVERY|verification environment is"
            + " available",
        "CHECKPOINT_FAILED|Candidate checkpoint was not"
            + " proven|CONTACT_SUPPORT|CONTACT_SUPPORT|contact support",
        "FRONTIER_CONFLICT|Accepted workspace frontier"
            + " changed|CONTACT_SUPPORT|WAIT_FOR_RECONCILIATION|contact support",
        "INTERNAL_PROTOCOL_CONFLICT|Generation protocol"
            + " conflict|CONTACT_SUPPORT|CONTACT_SUPPORT|contact support"
      })
  void workspaceGenerationCausalityOwnsPrimaryRecoveryCopyAndAction(
      WorkspaceGenerationFailureProjection.Category category,
      String title,
      String actionType,
      WorkspaceGenerationFailureProjection.RecommendedAction recommendedAction,
      String actionCopy) {
    WorkspaceGenerationFailureProjection failure =
        new WorkspaceGenerationFailureProjection(
            category,
            WorkspaceGenerationFailureProjection.Stage.VERIFICATION,
            category != WorkspaceGenerationFailureProjection.Category.ROLLBACK_UNPROVEN,
            category != WorkspaceGenerationFailureProjection.Category.NO_CANDIDATE,
            category == WorkspaceGenerationFailureProjection.Category.ROLLBACK_PROVEN,
            true,
            recommendedAction);

    RunTerminalOutcome outcome =
        RunTerminalOutcomeContract.project(
            "run-123e4567-e89b-12d3-a456-426614174000",
            "FAILED",
            "ALL_TASKS_FAILED",
            "raw provider error",
            0.25,
            5.0,
            null,
            false,
            false,
            failure);

    assertThat(outcome.code()).isEqualTo("WORKSPACE_GENERATION_" + category.name());
    assertThat(outcome.customerTitle()).isEqualTo(title);
    assertThat(outcome.nextAction().type()).isEqualTo(actionType);
    assertThat(outcome.nextAction().label()).contains(actionCopy);
    assertThat(outcome.customerMessage()).doesNotContain("raw provider error");
  }

  @ParameterizedTest
  @CsvSource({"COMPLETE,CLEAN_COMPLETION", "CANCELLED,RUN_CANCELLED", "ABORTED,RUN_ABORTED"})
  void generationFailureCannotOverrideSuccessOrUserStoppedTerminalStates(
      String status, String expectedCode) {
    WorkspaceGenerationFailureProjection failure =
        new WorkspaceGenerationFailureProjection(
            WorkspaceGenerationFailureProjection.Category.ROLLBACK_UNPROVEN,
            WorkspaceGenerationFailureProjection.Stage.ROLLBACK,
            false,
            true,
            false,
            true,
            WorkspaceGenerationFailureProjection.RecommendedAction.CONTACT_SUPPORT);

    RunTerminalOutcome outcome =
        RunTerminalOutcomeContract.project(
            "run-123e4567-e89b-12d3-a456-426614174000",
            status,
            null,
            null,
            0.25,
            5.0,
            null,
            false,
            null,
            failure);

    assertThat(outcome.code()).isEqualTo(expectedCode);
    assertThat(outcome.customerTitle()).doesNotContain("recovery is unresolved");
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
