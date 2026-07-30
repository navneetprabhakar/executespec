package com.navneet.executespec.engine;

import com.navneet.executespec.cost.RunCostTracker;

/**
 * JVM-local worker budget observation. Durable hard caps remain run-wide and authoritative;
 * per-solve dollar stops use task snapshots so sibling spend cannot consume them.
 */
final class WorkerLiveBudgetGuard {

  record Violation(String message, double spentUsd, Double capUsd) {}

  private WorkerLiveBudgetGuard() {}

  static Violation evaluate(
      WorkerTaskRequest req, Double runHardCapUsd, RunCostTracker tracker, long startEpochMs) {
    RunCostTracker.Snapshot runUsage =
        runHardCapUsd != null && runHardCapUsd > 0 ? tracker.snapshot(req.context().runId()) : null;
    double runSpent = runUsage == null ? 0.0 : runUsage.costUsd();
    if (runHardCapUsd != null && runHardCapUsd > 0 && runSpent >= runHardCapUsd) {
      return new Violation(
          "hard cap $"
              + String.format("%.2f", runHardCapUsd)
              + " exceeded mid-task (spent $"
              + String.format("%.2f", runSpent)
              + ")",
          runSpent,
          runHardCapUsd);
    }
    String task = taskViolation(req, tracker, startEpochMs);
    return task == null ? null : new Violation(task, 0.0, null);
  }

  static String taskViolation(WorkerTaskRequest req, RunCostTracker tracker, long startEpochMs) {
    return taskViolation(req, tracker, startEpochMs, true);
  }

  /**
   * Final shutdown-reserve turns may cross the ordinary task wall clock once, but they must still
   * respect explicit cost and token ceilings.
   */
  static String finalizerViolation(
      WorkerTaskRequest req, RunCostTracker tracker, long startEpochMs) {
    return taskViolation(req, tracker, startEpochMs, false);
  }

  static String guard(
      WorkerTaskRequest req,
      RunCostTracker tracker,
      long startEpochMs,
      WorkerDispatchMode dispatchMode) {
    return dispatchMode.finalizer()
        ? finalizerViolation(req, tracker, startEpochMs)
        : taskViolation(req, tracker, startEpochMs);
  }

  private static String taskViolation(
      WorkerTaskRequest req, RunCostTracker tracker, long startEpochMs, boolean enforceWallClock) {
    if (enforceWallClock
        && req.wallClockSeconds() != null
        && req.wallClockSeconds() > 0
        && startEpochMs > 0
        && System.currentTimeMillis() - startEpochMs >= req.wallClockSeconds() * 1000L) {
      return "task wall-clock ceiling reached";
    }
    RunCostTracker.Snapshot usage =
        tracker.snapshotTask(req.context().runId(), req.context().taskId());
    if (usage == null) usage = new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0);
    double costCap = req.dollarCap() == null ? -1.0 : req.dollarCap();
    if (costCap > 0 && usage.costUsd() >= costCap) {
      return "task cost ceiling $"
          + String.format("%.2f", costCap)
          + " reached (spent $"
          + String.format("%.2f", usage.costUsd())
          + ")";
    }
    long tokenCap = req.taskTotalTokenCap() == null ? -1L : req.taskTotalTokenCap();
    long totalTokens = usage.inputTokens() + usage.outputTokens();
    return tokenCap > 0 && totalTokens >= tokenCap
        ? "task token ceiling " + tokenCap + " reached (input+output=" + totalTokens + ")"
        : null;
  }
}
