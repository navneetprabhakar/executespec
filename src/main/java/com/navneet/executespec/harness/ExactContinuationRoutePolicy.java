package com.navneet.executespec.harness;

import java.util.Locale;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.LlmProvider;

/** Fail-closed admission and budget selection for an exact continuation route. */
final class ExactContinuationRoutePolicy {
  private ExactContinuationRoutePolicy() {}

  static Admission admit(
      AvailableProviderResolver resolver, String runId, String provider, String model) {
    if (resolver == null || provider == null || provider.isBlank()) {
      return Admission.blocked(provider, model);
    }
    try {
      String normalized = provider.trim().toUpperCase(Locale.ROOT);
      if ("GOOGLE".equals(normalized)) normalized = "GEMINI";
      return resolver.resolve(runId).contains(LlmProvider.valueOf(normalized))
          ? Admission.admitted()
          : Admission.blocked(provider, model);
    } catch (RuntimeException invalidOrUnavailable) {
      return Admission.blocked(provider, model);
    }
  }

  static TierConfig tierConfig(
      PlannedTask.Complexity complexity,
      String provider,
      String model,
      boolean projectRun,
      AgentTier lowComplexityStartTier,
      int[] profileThresholds,
      boolean forceToolChoiceOnSalvage) {
    WorkerTierBudgetPolicy.Budget budget =
        WorkerTierBudgetPolicy.forTask(complexity, projectRun, lowComplexityStartTier);
    return new TierConfig(
        provider,
        model,
        budget.tier(),
        budget.maxIterations(),
        budget.maxTokens(),
        profileThresholds[0] > 0 ? profileThresholds[0] : budget.stagnationWarnAfter(),
        profileThresholds[1] > 0 ? profileThresholds[1] : budget.stagnationForceAfter(),
        budget.wallClockSeconds(),
        forceToolChoiceOnSalvage);
  }

  static TaskOutcome failureOutcome(PlannedTask task, long taskStart) {
    return TaskOutcome.builder()
        .taskId(task.id())
        .taskTitle(task.title())
        .status(TaskOutcome.Status.WORKER_FAILED)
        .totalLatencyMs(System.currentTimeMillis() - taskStart)
        .toolCallsDispatched(0)
        .build();
  }

  record Admission(boolean allowed, String reason) {
    private static Admission admitted() {
      return new Admission(true, null);
    }

    private static Admission blocked(String provider, String model) {
      return new Admission(
          false,
          "Approved exact continuation route is unavailable; no provider call was made: "
              + provider
              + "/"
              + model);
    }
  }
}
