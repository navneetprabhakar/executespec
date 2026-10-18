package com.navneet.executespec.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;

/** Projects authoritative acceptance into task decisions after verification and rollback settle. */
final class BuildAcceptanceDecisions {

  private BuildAcceptanceDecisions() {}

  static GoalDonenessDecision goal(
      TaskOutcome source, boolean accepted, boolean unavailable, String reason) {
    GoalDonenessDecision prior = source.goalDonenessDecision();
    return new GoalDonenessDecision(
        accepted
            ? GoalDonenessState.DONE
            : unavailable ? GoalDonenessState.BLOCKED : GoalDonenessState.FAILED,
        accepted
            ? RunContinuationDecision.STOP_DONE
            : unavailable
                ? RunContinuationDecision.STOP_BLOCKED
                : RunContinuationDecision.STOP_FAILED,
        reason,
        "",
        prior == null ? source.taskTitle() : prior.goalSummary(),
        accepted ? List.of("Frozen task acceptance passed on the accepted generation") : List.of(),
        accepted ? List.of() : List.of(reason),
        List.of("authority=FROZEN_TASK_ACCEPTANCE"),
        unavailable
            ? List.of("Verification infrastructure must be restored before retrying")
            : List.of(),
        prior == null ? "" : prior.budgetCeilingReason(),
        prior == null ? 0 : prior.inputTokens(),
        prior == null ? 0 : prior.outputTokens(),
        prior == null ? 0 : prior.cacheReadTokens(),
        prior == null ? 0 : prior.cacheWriteTokens(),
        prior == null ? 0 : prior.reasoningTokens(),
        prior == null ? 0 : prior.totalTokens(),
        prior == null ? 0 : prior.costUsd(),
        prior == null ? source.totalLatencyMs() : prior.wallClockMs(),
        unavailable,
        !accepted && !unavailable);
  }

  static GoalContinuationPolicyDecision continuation(
      boolean accepted, boolean unavailable, String reason) {
    return new GoalContinuationPolicyDecision(
        accepted
            ? RunContinuationDecision.STOP_DONE
            : unavailable
                ? RunContinuationDecision.STOP_BLOCKED
                : RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        Optional.empty(),
        List.of(reason),
        accepted || unavailable
            ? Map.of("decisionAuthority", "FROZEN_TASK_ACCEPTANCE")
            : Map.of(
                "decisionAuthority", "FROZEN_TASK_ACCEPTANCE", "buildSmokeRetryRequired", true));
  }
}
