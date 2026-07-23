package com.navneet.executespec.harness;

import java.util.Locale;

import com.navneet.executespec.engine.WorkerResult;

/** Classifies bounded worker failure evidence that genuinely indicates provider breakdown. */
final class ProviderBehaviorPolicy {

  private ProviderBehaviorPolicy() {}

  static boolean isPoor(WorkerResult workerResult, String failureReason) {
    if (workerResult != null) {
      if (workerResult.outcome() == WorkerResult.Outcome.ERROR) return true;
      if (WorkerToolLogEvidence.countFileWrites(workerResult.toolCallLog()) == 0
          && breakdownOutcome(workerResult.outcome())) return true;

      // A clean completeTask with no explicit failure is not provider breakdown. Do not scan its
      // success summary for broad transport words: normal requirement prose such as "stable API
      // errors" previously matched "api error" and triggered a wasteful cross-provider rerun after
      // a green task (run 1e1762b9).
      if (workerResult.outcome() == WorkerResult.Outcome.COMPLETED
          && (failureReason == null || failureReason.isBlank())) return false;
    }

    String text =
        ((failureReason == null ? "" : failureReason)
                + " "
                + (workerResult == null || workerResult.finalAssistantText() == null
                    ? ""
                    : workerResult.finalAssistantText()))
            .toLowerCase(Locale.ROOT);
    return containsAny(
        text,
        "llm",
        "model provider",
        "provider error",
        "router",
        "rate limit",
        "429",
        "5xx",
        "timeout",
        "connection reset",
        "api error");
  }

  private static boolean breakdownOutcome(WorkerResult.Outcome outcome) {
    return outcome == WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE
        || outcome == WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED
        || outcome == WorkerResult.Outcome.COMPLETED_BY_STAGNATION
        || outcome == WorkerResult.Outcome.WALL_CLOCK_EXCEEDED
        || outcome == WorkerResult.Outcome.ABSOLUTE_DEADLINE_EXCEEDED;
  }

  private static boolean containsAny(String text, String... needles) {
    for (String needle : needles) {
      if (text.contains(needle)) return true;
    }
    return false;
  }
}
