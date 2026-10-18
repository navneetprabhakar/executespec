package com.navneet.executespec.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.router.ModelProviderScore;

/** Pure BUILD retry-policy and outcome projection outside the executor god class. */
final class BuildContinuationOutcomes {

  private BuildContinuationOutcomes() {}

  static boolean sameProviderRetryRequired(
      GoalContinuationPolicyDecision decision, TaskOutcome outcome) {
    if (decision == null
        || outcome == null
        || outcome.retryAttempted()
        || Boolean.TRUE.equals(outcome.smokeTestPassed())) return false;
    if (!Boolean.TRUE.equals(decision.policy().get("buildSmokeRetryRequired"))) return false;
    if (decision.decision() == RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER) return true;
    return decision.decision() == RunContinuationDecision.STOP_BLOCKED
        && "ROUTE_PINNED".equals(decision.policy().get("blockedAutomaticContinuationReason"));
  }

  static ModelProviderScore sameRouteScore(PlannedTask task, TaskOutcome outcome) {
    return new ModelProviderScore(
        outcome.dispatchedProvider(),
        outcome.dispatchedModel(),
        TaskCategoryClassifier.classify(task),
        0.0,
        0.0,
        0,
        0,
        0.0,
        List.of("same-provider deterministic build retry"));
  }

  static GoalContinuationPolicyDecision sameRouteDecision(ModelProviderScore sameRoute) {
    return new GoalContinuationPolicyDecision(
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        Optional.of(sameRoute),
        List.of("retry the rejected BUILD generation with exact smoke-test evidence"));
  }

  static GoalContinuationPolicyDecision requireSmokeRetry(GoalContinuationPolicyDecision decision) {
    Map<String, Object> policy = new LinkedHashMap<>(decision.policy());
    policy.put("buildSmokeRetryRequired", true);
    return new GoalContinuationPolicyDecision(
        decision.decision(), decision.selectedCandidate(), decision.reasons(), policy);
  }

  static boolean approvalBlocked(GoalContinuationPolicyDecision decision) {
    return decision != null
        && decision.decision() == RunContinuationDecision.STOP_BLOCKED
        && decision.selectedCandidate().isPresent()
        && approvalEligible(decision);
  }

  private static boolean approvalEligible(GoalContinuationPolicyDecision decision) {
    Map<String, Object> policy = decision.policy();
    Object reason = policy == null ? null : policy.get("blockedAutomaticContinuationReason");
    if ("ROUTE_PINNED".equals(String.valueOf(reason))) return false;
    if ("BENCHMARK_PRIOR_REQUIRED".equals(String.valueOf(reason))
        || "EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED".equals(String.valueOf(reason))) {
      return true;
    }
    ModelProviderScore candidate = decision.selectedCandidate().orElse(null);
    if (candidate == null || policy == null) return false;
    if (Boolean.TRUE.equals(policy.get("requireBenchmarkPriorForAutomaticContinuation"))
        && !candidate.benchmarkQualityPriorAvailable()) return true;
    return Boolean.TRUE.equals(policy.get("requireConfirmationBeforeExpensiveContinuation"));
  }

  static TaskOutcome merge(
      TaskOutcome initial,
      TaskOutcome rerun,
      GoalContinuationPolicyDecision decision,
      ModelProviderScore candidate) {
    if (rerun == null) return initial;
    boolean sameProvider =
        decision != null
            && decision.decision() == RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER;
    TaskOutcome.Status status =
        sameProvider && rerun.status() == TaskOutcome.Status.SUCCESS
            ? TaskOutcome.Status.SUCCESS_AFTER_RETRY
            : rerun.status();
    return TaskOutcome.builder()
        .taskId(rerun.taskId())
        .taskTitle(rerun.taskTitle())
        .status(status)
        .workerOutcome(sameProvider ? initial.workerOutcome() : rerun.workerOutcome())
        .retryAttempted(sameProvider || rerun.retryAttempted())
        .retryWorkerOutcome(sameProvider ? rerun.workerOutcome() : rerun.retryWorkerOutcome())
        .smokeTestPassed(rerun.smokeTestPassed())
        .smokeTestExitCode(rerun.smokeTestExitCode())
        .smokeTestOutput(rerun.smokeTestOutput())
        .totalLatencyMs(initial.totalLatencyMs() + rerun.totalLatencyMs())
        .toolCallsDispatched(initial.toolCallsDispatched() + rerun.toolCallsDispatched())
        .dispatchedProvider(rerun.dispatchedProvider())
        .dispatchedModel(rerun.dispatchedModel())
        .goalDonenessDecision(rerun.goalDonenessDecision())
        .continuationDecision(rerun.continuationDecision())
        .continuationAction(decision == null ? null : decision.decision())
        .continuationActionProvider(candidate == null ? null : candidate.provider())
        .continuationActionModel(candidate == null ? null : candidate.model())
        .acceptanceDiagnostics(rerun.acceptanceDiagnostics())
        .build();
  }
}
