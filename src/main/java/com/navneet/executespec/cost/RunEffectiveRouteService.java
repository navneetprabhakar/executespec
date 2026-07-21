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
}
