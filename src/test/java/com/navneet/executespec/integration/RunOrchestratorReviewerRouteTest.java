package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;

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
    return TaskOutcome.builder()
        .taskId("T1")
        .taskTitle("Task")
        .status(status)
        .continuationAction(action)
        .continuationActionProvider(actionProvider)
        .continuationActionModel(actionModel)
        .dispatchedProvider(dispatchedProvider)
        .dispatchedModel(dispatchedModel)
        .build();
  }
}
