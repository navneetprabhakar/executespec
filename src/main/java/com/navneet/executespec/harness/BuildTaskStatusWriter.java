package com.navneet.executespec.harness;

import java.util.Map;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.repository.AgentTaskRepository;

/** Fail-closed task transition adapter used by the BUILD attempt supervisor. */
final class BuildTaskStatusWriter {

  private BuildTaskStatusWriter() {}

  static TaskStatusPersistenceService.Result persist(
      TaskStatusPersistenceService injected,
      AgentTaskRepository tasks,
      RunCostTracker costs,
      Map<String, Integer> recoveryAttempts,
      String runId,
      String taskId,
      String status,
      String failureReason) {
    String key = runId.substring(0, Math.min(8, runId.length())) + "_" + taskId;
    TaskStatusPersistenceService persistence =
        injected != null ? injected : new TaskStatusPersistenceService(tasks, costs);
    return persistence.persist(
        runId, taskId, status, failureReason, null, recoveryAttempts.get(key));
  }
}
