package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.ModelProviderScore;

import org.junit.jupiter.api.Test;

class RunOrchestratorReviewerRouteTest {

  @Test
  void noContinuationAction_usesDefaultRoute() {
    RunOrchestrator.ProviderModel route =
        RunOrchestrator.reviewerRouteFor(
            "deepseek",
            "deepseek-v4-pro-native",
            result(
                task(
                    TaskOutcome.Status.SUCCESS,
                    null,
                    null,
                    null,
                    "deepseek",
                    "deepseek-v4-pro-native")));

    assertThat(route.provider()).isEqualTo("deepseek");
    assertThat(route.model()).isEqualTo("deepseek-v4-pro-native");
  }

  @Test
  void successfulStepUpContinuation_promotesReviewerRoute() {
    RunOrchestrator.ProviderModel route =
        RunOrchestrator.reviewerRouteFor(
            "deepseek",
            "deepseek-v4-pro-native",
            result(
                task(
                    TaskOutcome.Status.SUCCESS,
                    RunContinuationDecision.STEP_UP_MODEL,
                    "anthropic",
                    "claude-sonnet-4-6",
                    "anthropic",
                    "claude-sonnet-4-6")));

    assertThat(route.provider()).isEqualTo("anthropic");
    assertThat(route.model()).isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void failedContinuationAction_keepsDefaultRoute() {
    RunOrchestrator.ProviderModel route =
        RunOrchestrator.reviewerRouteFor(
            "deepseek",
            "deepseek-v4-pro-native",
            result(
                task(
                    TaskOutcome.Status.WORKER_FAILED,
                    RunContinuationDecision.STEP_UP_MODEL,
                    "anthropic",
                    "claude-sonnet-4-6",
                    "anthropic",
                    "claude-sonnet-4-6")));

    assertThat(route.provider()).isEqualTo("deepseek");
    assertThat(route.model()).isEqualTo("deepseek-v4-pro-native");
  }

  @Test
  void latestSuccessfulQualityContinuationWins() {
    RunOrchestrator.ProviderModel route =
        RunOrchestrator.reviewerRouteFor(
            "deepseek",
            "deepseek-v4-pro-native",
            result(
                task(
                    TaskOutcome.Status.SUCCESS,
                    RunContinuationDecision.STEP_UP_MODEL,
                    "openai",
                    "gpt-5.4",
                    "openai",
                    "gpt-5.4"),
                task(
                    TaskOutcome.Status.COMPLETED_WITH_WARNING,
                    RunContinuationDecision.CHANGE_PROVIDER,
                    "anthropic",
                    "claude-sonnet-4-6",
                    "anthropic",
                    "claude-sonnet-4-6")));

    assertThat(route.provider()).isEqualTo("anthropic");
    assertThat(route.model()).isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void stepDownContinuationDoesNotPromoteReviewer() {
    RunOrchestrator.ProviderModel route =
        RunOrchestrator.reviewerRouteFor(
            "anthropic",
            "claude-sonnet-4-6",
            result(
                task(
                    TaskOutcome.Status.SUCCESS,
                    RunContinuationDecision.STEP_DOWN_MODEL,
                    "deepseek",
                    "deepseek-v4-pro-native",
                    "deepseek",
                    "deepseek-v4-pro-native")));

    assertThat(route.provider()).isEqualTo("anthropic");
    assertThat(route.model()).isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void unresolvedReviewerVerdictBuildsLowQualityGoalDecision() {
    var decision =
        RunOrchestrator.reviewerLowQualityGoalDecision(
            ReviewerResult.builder().overall(ReviewerResult.Overall.UNRESOLVED_ISSUES).build(),
            false);

    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_FAILED);
    assertThat(decision.reason()).contains("UNRESOLVED_ISSUES");
    assertThat(decision.incompleteItems())
        .contains("reviewer did not prove the implementation is acceptable");
    assertThat(decision.anotherModelProviderMightHelp()).isTrue();
  }

  @Test
  void reviewerContinuationTargetUsesLatestUsableWorkerTask() {
    var target =
        RunOrchestrator.reviewerContinuationTarget(
            result(
                task(
                    "T1",
                    TaskOutcome.Status.SUCCESS,
                    null,
                    null,
                    null,
                    "deepseek",
                    "deepseek-v4-pro-native"),
                task(
                    "T2",
                    TaskOutcome.Status.WORKER_FAILED,
                    null,
                    null,
                    null,
                    "anthropic",
                    "claude-sonnet-4-6")));

    assertThat(target).isPresent();
    assertThat(target.get().taskId()).isEqualTo("T1");
  }

  @Test
  void reviewerProviderPreferenceOrderStartsWithCurrentProviderAndDedupes() {
    assertThat(RunOrchestrator.reviewerProviderPreferenceOrder("Anthropic"))
        .containsExactly("Anthropic", "openai", "gemini", "deepseek", "openrouter", "fireworks");
  }

  @Test
  void postReviewActionGateOnlyExecutesQualityUpgrades() {
    assertThat(
            RunOrchestrator.actionablePostReviewContinuationDecision(
                decision(RunContinuationDecision.STEP_UP_MODEL)))
        .isTrue();
    assertThat(
            RunOrchestrator.actionablePostReviewContinuationDecision(
                decision(RunContinuationDecision.CHANGE_PROVIDER)))
        .isTrue();
    assertThat(
            RunOrchestrator.actionablePostReviewContinuationDecision(
                decision(RunContinuationDecision.STEP_DOWN_MODEL)))
        .isFalse();
    assertThat(
            RunOrchestrator.actionablePostReviewContinuationDecision(
                decision(RunContinuationDecision.STOP_BLOCKED)))
        .isFalse();
  }

  @Test
  void postReviewContinuationUsageSeedsSelectedActionIntoRetryBudget() {
    ExecutionResult previous =
        result(
            task(
                "T1",
                TaskOutcome.Status.SUCCESS,
                RunContinuationDecision.CHANGE_PROVIDER,
                "anthropic",
                "claude-sonnet-4-6",
                "anthropic",
                "claude-sonnet-4-6"));
    TaskOutcome target =
        task(
            "T2",
            TaskOutcome.Status.SUCCESS,
            null,
            null,
            null,
            "deepseek",
            "deepseek-v4-pro-native");

    var usage =
        RunOrchestrator.postReviewContinuationUsage(
            previous, target, decision(RunContinuationDecision.STEP_UP_MODEL));

    assertThat(usage.providerSwitches()).isEqualTo(1);
    assertThat(usage.modelStepUps()).isEqualTo(1);
    assertThat(usage.modelStepDowns()).isZero();
  }

  @Test
  void fullTaskKeyPreservesAlreadyPrefixedIds() {
    assertThat(RunOrchestrator.fullTaskKey("12345678-aaaa", "T1")).isEqualTo("12345678_T1");
    assertThat(RunOrchestrator.fullTaskKey("12345678-aaaa", "12345678_T1"))
        .isEqualTo("12345678_T1");
  }

  private static ExecutionResult result(TaskOutcome... tasks) {
    return ExecutionResult.builder().perTask(List.of(tasks)).build();
  }

  private static TaskOutcome task(
      TaskOutcome.Status status,
      RunContinuationDecision action,
      String actionProvider,
      String actionModel,
      String dispatchedProvider,
      String dispatchedModel) {
    return task(
        "T1", status, action, actionProvider, actionModel, dispatchedProvider, dispatchedModel);
  }

  private static TaskOutcome task(
      String taskId,
      TaskOutcome.Status status,
      RunContinuationDecision action,
      String actionProvider,
      String actionModel,
      String dispatchedProvider,
      String dispatchedModel) {
    return TaskOutcome.builder()
        .taskId(taskId)
        .taskTitle("Task")
        .status(status)
        .continuationAction(action)
        .continuationActionProvider(actionProvider)
        .continuationActionModel(actionModel)
        .dispatchedProvider(dispatchedProvider)
        .dispatchedModel(dispatchedModel)
        .build();
  }

  private static GoalContinuationPolicyDecision decision(RunContinuationDecision decision) {
    return new GoalContinuationPolicyDecision(
        decision,
        Optional.of(
            new ModelProviderScore(
                "anthropic",
                "claude-sonnet-4-6",
                "PROJECT_PATCH",
                95.0,
                3.0,
                1000L,
                2000L,
                70.0,
                true,
                List.of("test candidate"))),
        List.of("test decision"));
  }
}
