package com.navneet.executespec.harness;

import com.navneet.executespec.cost.RunCostTracker;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Captures one task-attempt usage delta without coupling transaction control to tracker arithmetic.
 */
@Component
@RequiredArgsConstructor
public class BuildTaskUsageEvidence {

  private static final RunCostTracker.Snapshot ZERO =
      new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0, 0);

  private final RunCostTracker costTracker;

  public RunCostTracker.Snapshot snapshot(String runId, String taskId) {
    return costTracker == null ? ZERO : costTracker.snapshotTask(runId, taskId);
  }

  public RunCostTracker.Snapshot delta(
      RunCostTracker.Snapshot before, String runId, String taskId) {
    RunCostTracker.Snapshot start = before == null ? ZERO : before;
    RunCostTracker.Snapshot end = snapshot(runId, taskId);
    return new RunCostTracker.Snapshot(
        Math.max(0, end.inputTokens() - start.inputTokens()),
        Math.max(0, end.outputTokens() - start.outputTokens()),
        Math.max(0, end.cacheWriteTokens() - start.cacheWriteTokens()),
        Math.max(0, end.cacheReadTokens() - start.cacheReadTokens()),
        Math.max(0.0, end.costUsd() - start.costUsd()),
        Math.max(0, end.calls() - start.calls()),
        Math.max(0, end.reasoningTokens() - start.reasoningTokens()));
  }
}
