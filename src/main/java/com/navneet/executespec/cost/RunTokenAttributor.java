package com.navneet.executespec.cost;

import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns per-phase + per-task token attribution for V2 runs.
 *
 * <p>Extracted from {@link com.navneet.executespec.integration.RunOrchestrator} as the first step
 * of the strangler-pattern refactor documented in {@code docs/plans/v2-orchestrator-refactor.md}.
 * The orchestrator previously held this logic as private methods ({@code persistPhaseTokens} +
 * {@code distributeWorkerTokens}) alongside pipeline control flow. Moving it here shrinks
 * RunOrchestrator and establishes a bounded context for cost accounting that future additions
 * (test-gen tokens, validation-report tokens) can grow into.
 *
 * <p>Semantics are preserved bit-for-bit from the inlined versions — this is pure code movement,
 * not a behaviour change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunTokenAttributor {

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final RunCostTracker costTracker;
  private final RunEffectiveRouteService effectiveRoutes;
  private final RunStepUsageRepository usageRepository;

  public enum Phase {
    PLANNER,
    REVIEWER
  }

  /**
   * Repair run-level usage after an interrupted phase from the immutable physical-attempt ledger.
   * Normal phase attribution is incremental and executes only after a phase returns; a JVM crash
   * mid-worker can therefore leave hundreds of thousands of already-recorded task tokens absent
   * from the run summary. This operation is idempotent and raise-only.
   */
  public void reconcileRunTotalsFromLedger(String runId) {
    long input = safeLong(usageRepository.sumSuccessfulInputTokensByRunId(runId));
    long output = safeLong(usageRepository.sumSuccessfulOutputTokensByRunId(runId));
    double cost =
        java.util.Optional.ofNullable(usageRepository.sumKnownCostByRunId(runId)).orElse(0d);
    runRepo.raiseUsageTotalsToAtLeast(
        runId,
        RunCostTracker.saturatedInt(input),
        RunCostTracker.saturatedInt(output),
        Math.max(0d, cost));
  }

  /**
   * Attribute tokens consumed in a single pipeline phase (PLANNER / REVIEWER). Persists per-phase
   * columns on the run AND increments the run totals so the "Run Stats" panel reflects cumulative
   * usage even if the in-memory tracker is later evicted (deploy restart, accumulator eviction).
   */
  public void attributePhase(
      String runId, Phase phase, long inTok, long outTok, String providerUpper, String model) {
    int persistedIn = RunCostTracker.saturatedInt(inTok);
    int persistedOut = RunCostTracker.saturatedInt(outTok);
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              switch (phase) {
                case PLANNER -> {
                  r.setPlannerInputTokens(persistedIn);
                  r.setPlannerOutputTokens(persistedOut);
                  r.setPlannerProvider(providerUpper);
                  r.setPlannerActualModel(model);
                }
                case REVIEWER -> {
                  r.setReviewerInputTokens(persistedIn);
                  r.setReviewerOutputTokens(persistedOut);
                  r.setReviewerProvider(providerUpper);
                  r.setReviewerActualModel(model);
                }
              }
              runRepo.save(r);
            });
    if (inTok > 0 || outTok > 0) {
      try {
        runRepo.addTokens(runId, persistedIn, persistedOut);
      } catch (Exception e) {
        log.warn("attributePhase addTokens failed run={}: {}", runId, e.getMessage());
      }
    }
  }

  /**
   * Attribute an additional pass for a phase that may run more than once. Unlike {@link
   * #attributePhase}, this adds to the phase-specific columns instead of replacing them, while
   * still adding only the provided delta to the run total.
   */
  public void attributePhaseAdditional(
      String runId, Phase phase, long inTok, long outTok, String providerUpper, String model) {
    int persistedIn = RunCostTracker.saturatedInt(inTok);
    int persistedOut = RunCostTracker.saturatedInt(outTok);
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              switch (phase) {
                case PLANNER -> {
                  r.setPlannerInputTokens(
                      saturatingAdd(safe(r.getPlannerInputTokens()), persistedIn));
                  r.setPlannerOutputTokens(
                      saturatingAdd(safe(r.getPlannerOutputTokens()), persistedOut));
                  r.setPlannerProvider(providerUpper);
                  r.setPlannerActualModel(model);
                }
                case REVIEWER -> {
                  r.setReviewerInputTokens(
                      saturatingAdd(safe(r.getReviewerInputTokens()), persistedIn));
                  r.setReviewerOutputTokens(
                      saturatingAdd(safe(r.getReviewerOutputTokens()), persistedOut));
                  r.setReviewerProvider(providerUpper);
                  r.setReviewerActualModel(model);
                }
              }
              runRepo.save(r);
            });
    if (inTok > 0 || outTok > 0) {
      try {
        runRepo.addTokens(runId, persistedIn, persistedOut);
      } catch (Exception e) {
        log.warn("attributePhaseAdditional addTokens failed run={}: {}", runId, e.getMessage());
      }
    }
  }

  /**
   * Distribute worker-phase tokens (executor aggregate) across per-task rows so the task-level
   * token columns are populated. Real per-task snapshots come from {@link
   * RunCostTracker#snapshotTask}; any residual (tool-only retries that never hit the LLM) is
   * divided equally so per-task columns still reconcile to the run total.
   */
  public void attributeWorker(
      String runId,
      ExecutionResult execResult,
      long totalIn,
      long totalOut,
      String providerUpper,
      String model) {
    int persistedTotalIn = RunCostTracker.saturatedInt(totalIn);
    int persistedTotalOut = RunCostTracker.saturatedInt(totalOut);
    int n = execResult.perTask().size();
    if (n == 0) return;
    if (totalIn > 0 || totalOut > 0) {
      try {
        runRepo.addTokens(runId, persistedTotalIn, persistedTotalOut);
      } catch (Exception e) {
        log.warn("attributeWorker addTokens failed run={}: {}", runId, e.getMessage());
      }
    }
    String idPrefix = runId.substring(0, 8) + "_";
    int sumIn = 0, sumOut = 0;
    java.util.Map<String, int[]> realPerTask = new java.util.HashMap<>();
    for (var outcome : execResult.perTask()) {
      RunCostTracker.Snapshot s = costTracker.snapshotTask(runId, outcome.taskId());
      int taskIn = RunCostTracker.saturatedInt(s.inputTokens());
      int taskOut = RunCostTracker.saturatedInt(s.outputTokens());
      realPerTask.put(outcome.taskId(), new int[] {taskIn, taskOut});
      sumIn = saturatingAdd(sumIn, taskIn);
      sumOut = saturatingAdd(sumOut, taskOut);
    }
    int residualIn = Math.max(0, persistedTotalIn - sumIn);
    int residualOut = Math.max(0, persistedTotalOut - sumOut);
    int residualPerTaskIn = residualIn / n;
    int residualPerTaskOut = residualOut / n;
    // RR-1561 — integer division drops up to n-1 tokens per direction. The run total at addTokens
    // above already counted them, so discarding the remainder made exact reconciliation between
    // the per-task columns and the run total impossible BY CONSTRUCTION, defeating the stated
    // purpose of this residual pass. The remainder is handed to the first tasks, one token each.
    int remainderIn = residualIn - residualPerTaskIn * n;
    int remainderOut = residualOut - residualPerTaskOut * n;
    int taskIndex = 0;
    for (var outcome : execResult.perTask()) {
      String taskRowId = idPrefix + outcome.taskId();
      int[] real = realPerTask.get(outcome.taskId());
      int finalIn = saturatingAdd(real[0], residualPerTaskIn + (taskIndex < remainderIn ? 1 : 0));
      int finalOut =
          saturatingAdd(real[1], residualPerTaskOut + (taskIndex < remainderOut ? 1 : 0));
      taskIndex++;
      // 2026-05-02 — write the model the worker ACTUALLY ran on (tier-resolved from
      // SequentialExecutor.tierConfigFor) instead of the run-level default. Previously every
      // task row got the run default (e.g. claude-haiku for anthropic), masking the workhorse/
      // planning escalation for MEDIUM/HIGH tasks. b171fa52 had 14 MEDIUM + 7 HIGH tasks all
      // showing Haiku in the DB even though they ran on Sonnet at runtime. Falls back to the
      // run-default for legacy outcomes (TaskOutcome from older serialised runs lacked the
      // dispatchedProvider/Model fields).
      String taskProvider =
          outcome.dispatchedProvider() != null && !outcome.dispatchedProvider().isBlank()
              ? outcome.dispatchedProvider().toUpperCase()
              : providerUpper;
      String taskModel =
          outcome.dispatchedModel() != null && !outcome.dispatchedModel().isBlank()
              ? outcome.dispatchedModel()
              : model;
      // C1 Step 4 — the requested route above is what the executor ASKED for. The route that
      // actually served the task comes from the physical-attempt ledger; failover and managed-AI
      // tier locks make the two legitimately diverge. Recording only the requested route fed a
      // wrong quality prior into ModelProviderCandidateService.
      EffectiveRouteProjection route = effectiveRoutes.forTask(runId, outcome.taskId());
      taskRepo
          .findById(taskRowId)
          .ifPresent(
              t -> {
                t.setInputTokens(finalIn);
                t.setOutputTokens(finalOut);
                t.setProvider(taskProvider);
                t.setModel(taskModel);
                applyEffectiveRoute(t, route, taskProvider, taskModel);
                taskRepo.save(t);
              });
    }
  }

  /**
   * Writes the projected physical route onto the task row. The requested route is used ONLY when
   * the ledger proves nothing, and in that case the attribution stays {@code UNATTRIBUTED} so a
   * consumer can tell an unproven fallback from a ledger-proven route. A {@code MIXED} task keeps
   * null provider/model — collapsing it to one provider would defeat the projection.
   */
  private static void applyEffectiveRoute(
      com.navneet.executespec.state.entity.AgentTask task,
      EffectiveRouteProjection route,
      String fallbackProvider,
      String fallbackModel) {
    task.setEffectiveRouteAttribution(route.attribution().name());
    switch (route.attribution()) {
      case LEDGER -> {
        task.setEffectiveProvider(
            route.provider() == null ? null : route.provider().toUpperCase(java.util.Locale.ROOT));
        task.setEffectiveModel(route.model());
      }
      case MIXED -> {
        task.setEffectiveProvider(null);
        task.setEffectiveModel(null);
      }
      case UNATTRIBUTED -> {
        task.setEffectiveProvider(fallbackProvider);
        task.setEffectiveModel(fallbackModel);
      }
    }
  }

  /**
   * Attribute an additional worker pass for one resumed task. Resume-aware executor results include
   * synthetic SKIPPED outcomes for earlier terminal tasks, so the normal aggregate distributor
   * would revisit unrelated rows. This method records the run-total delta and rewrites only the
   * target task row from its real task snapshot.
   */
  public void attributeWorkerAdditionalTask(
      String runId,
      String taskId,
      long totalIn,
      long totalOut,
      String providerUpper,
      String model) {
    if (runId == null || runId.length() < 8 || taskId == null || taskId.isBlank()) {
      return;
    }
    if (totalIn > 0 || totalOut > 0) {
      try {
        runRepo.addTokens(
            runId, RunCostTracker.saturatedInt(totalIn), RunCostTracker.saturatedInt(totalOut));
      } catch (Exception e) {
        log.warn(
            "attributeWorkerAdditionalTask addTokens failed run={}: {}", runId, e.getMessage());
      }
    }
    String taskRowId =
        taskId.startsWith(runId.substring(0, 8) + "_")
            ? taskId
            : runId.substring(0, 8) + "_" + taskId;
    String logicalTaskId = taskRowId.substring(runId.substring(0, 8).length() + 1);
    RunCostTracker.Snapshot snapshot = costTracker.snapshotTask(runId, logicalTaskId);
    taskRepo
        .findById(taskRowId)
        .ifPresent(
            task -> {
              task.setInputTokens(RunCostTracker.saturatedInt(snapshot.inputTokens()));
              task.setOutputTokens(RunCostTracker.saturatedInt(snapshot.outputTokens()));
              task.setProvider(providerUpper);
              task.setModel(model);
              // Re-project after a continuation rerun: leaving the previous pass's effective route
              // in place would make the row claim a route that no longer served the task.
              applyEffectiveRoute(
                  task, effectiveRoutes.forTask(runId, logicalTaskId), providerUpper, model);
              taskRepo.save(task);
            });
  }

  private static int safe(Integer value) {
    return value == null ? 0 : Math.max(0, value);
  }

  private static long safeLong(Long value) {
    return value == null ? 0L : Math.max(0L, value);
  }

  private static int saturatingAdd(int left, int right) {
    return RunCostTracker.saturatedInt((long) left + right);
  }
}
