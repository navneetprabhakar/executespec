package com.navneet.executespec.cost;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Single read seam for the physical route that actually served a run or one of its tasks.
 *
 * <p>Callers must not re-derive this from a requested/tier-resolved value. {@code agent_task},
 * {@code RunDetail} and the benchmark attribution path all read through here (or through {@link
 * EffectiveRouteProjection} directly) so "effective route" has exactly one definition: the distinct
 * successful {@code (provider, model)} pairs in the canonical {@link RunStepUsage} ledger.
 */
@Service
@RequiredArgsConstructor
public class RunEffectiveRouteService {

  private final RunStepUsageRepository repository;

  /** Effective route across every physical attempt recorded for the run. */
  public EffectiveRouteProjection forRun(String runId) {
    if (runId == null || runId.isBlank()) {
      return EffectiveRouteProjection.UNATTRIBUTED;
    }
    return EffectiveRouteProjection.from(repository.findByRunIdOrderByCreatedAtAsc(runId));
  }

  /** Effective route across the physical attempts recorded for one plan task. */
  public EffectiveRouteProjection forTask(String runId, String taskId) {
    if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
      return EffectiveRouteProjection.UNATTRIBUTED;
    }
    List<RunStepUsage> rows = repository.findByRunIdAndTaskIdOrderByCreatedAtAsc(runId, taskId);
    return EffectiveRouteProjection.from(rows);
  }

  /** Physical route(s) that served planning, excluding the separately attributed classifier. */
  public EffectiveRouteProjection forPlanner(String runId) {
    return forRunRowsMatching(runId, row -> isPlannerRole(row.getAgentRole()));
  }

  /** Physical route(s) that served reviewer worker passes. */
  public EffectiveRouteProjection forReviewer(String runId) {
    return forRunRowsMatching(
        runId,
        row ->
            row.getTaskId() != null
                && row.getTaskId().toLowerCase(java.util.Locale.ROOT).contains("-reviewer-"));
  }

  /**
   * Most recent successful physical route for a task. Continuation policy needs the route that
   * produced the terminal observation, while the durable task projection intentionally remains
   * {@code MIXED} when more than one route served earlier turns.
   */
  public EffectiveRouteProjection latestSuccessfulForTask(String runId, String taskId) {
    if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
      return EffectiveRouteProjection.UNATTRIBUTED;
    }
    List<RunStepUsage> rows = repository.findByRunIdAndTaskIdOrderByCreatedAtAsc(runId, taskId);
    if (rows == null) return EffectiveRouteProjection.UNATTRIBUTED;
    for (int i = rows.size() - 1; i >= 0; i--) {
      RunStepUsage row = rows.get(i);
      if (row != null && "SUCCESS".equalsIgnoreCase(row.getAttemptStatus())) {
        return EffectiveRouteProjection.from(List.of(row));
      }
    }
    return EffectiveRouteProjection.UNATTRIBUTED;
  }

  private EffectiveRouteProjection forRunRowsMatching(
      String runId, java.util.function.Predicate<RunStepUsage> predicate) {
    if (runId == null || runId.isBlank()) return EffectiveRouteProjection.UNATTRIBUTED;
    List<RunStepUsage> rows = repository.findByRunIdOrderByCreatedAtAsc(runId);
    if (rows == null || rows.isEmpty()) return EffectiveRouteProjection.UNATTRIBUTED;
    return EffectiveRouteProjection.from(rows.stream().filter(predicate).toList());
  }

  private static boolean isPlannerRole(String agentRole) {
    return "OutlinePlannerAgent".equalsIgnoreCase(agentRole)
        || "TaskExpanderAgent".equalsIgnoreCase(agentRole)
        || "CoherencePlannerAgent".equalsIgnoreCase(agentRole);
  }
}
