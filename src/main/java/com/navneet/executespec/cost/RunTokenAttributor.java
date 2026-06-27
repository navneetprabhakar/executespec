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

  public enum Phase {
    PLANNER,
    REVIEWER
  }

  /**
   * Attribute tokens consumed in a single pipeline phase (PLANNER / REVIEWER). Persists per-phase
   * columns on the run AND increments the run totals so the "Run Stats" panel reflects cumulative
   * usage even if the in-memory tracker is later evicted (deploy restart, accumulator eviction).
   */
  public void attributePhase(
      String runId, Phase phase, int inTok, int outTok, String providerUpper, String model) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              switch (phase) {
                case PLANNER -> {
                  r.setPlannerInputTokens(inTok);
                  r.setPlannerOutputTokens(outTok);
                  r.setPlannerProvider(providerUpper);
                  r.setPlannerActualModel(model);
                }
                case REVIEWER -> {
                  r.setReviewerInputTokens(inTok);
                  r.setReviewerOutputTokens(outTok);
                  r.setReviewerProvider(providerUpper);
                  r.setReviewerActualModel(model);
                }
              }
              runRepo.save(r);
            });
    if (inTok > 0 || outTok > 0) {
      try {
        runRepo.addTokens(runId, inTok, outTok);
      } catch (Exception e) {
        log.warn("attributePhase addTokens failed run={}: {}", runId, e.getMessage());
      }
    }
  }

  /**
   * Attribute an additional pass for a phase that may run more than once. Unlike {@link
   * #attributePhase}, this adds to the phase-specific columns instead of replacing them, while still
   * adding only the provided delta to the run total.
   */
  public void attributePhaseAdditional(
      String runId, Phase phase, int inTok, int outTok, String providerUpper, String model) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              switch (phase) {
                case PLANNER -> {
                  r.setPlannerInputTokens(safe(r.getPlannerInputTokens()) + Math.max(0, inTok));
                  r.setPlannerOutputTokens(safe(r.getPlannerOutputTokens()) + Math.max(0, outTok));
                  r.setPlannerProvider(providerUpper);
                  r.setPlannerActualModel(model);
                }
                case REVIEWER -> {
                  r.setReviewerInputTokens(safe(r.getReviewerInputTokens()) + Math.max(0, inTok));
                  r.setReviewerOutputTokens(
                      safe(r.getReviewerOutputTokens()) + Math.max(0, outTok));
                  r.setReviewerProvider(providerUpper);
                  r.setReviewerActualModel(model);
                }
              }
              runRepo.save(r);
            });
    if (inTok > 0 || outTok > 0) {
      try {
        runRepo.addTokens(runId, inTok, outTok);
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
      int totalIn,
      int totalOut,
      String providerUpper,
      String model) {
    int n = execResult.perTask().size();
    if (n == 0) return;
    if (totalIn > 0 || totalOut > 0) {
      try {
        runRepo.addTokens(runId, totalIn, totalOut);
      } catch (Exception e) {
        log.warn("attributeWorker addTokens failed run={}: {}", runId, e.getMessage());
      }
    }
    String idPrefix = runId.substring(0, 8) + "_";
    int sumIn = 0, sumOut = 0;
    java.util.Map<String, int[]> realPerTask = new java.util.HashMap<>();
    for (var outcome : execResult.perTask()) {
      RunCostTracker.Snapshot s = costTracker.snapshotTask(runId, outcome.taskId());
      realPerTask.put(outcome.taskId(), new int[] {s.inputTokens(), s.outputTokens()});
      sumIn += s.inputTokens();
      sumOut += s.outputTokens();
    }
    int residualIn = Math.max(0, totalIn - sumIn);
    int residualOut = Math.max(0, totalOut - sumOut);
    int residualPerTaskIn = residualIn / n;
    int residualPerTaskOut = residualOut / n;
    for (var outcome : execResult.perTask()) {
      String taskRowId = idPrefix + outcome.taskId();
      int[] real = realPerTask.get(outcome.taskId());
      int finalIn = real[0] + residualPerTaskIn;
      int finalOut = real[1] + residualPerTaskOut;
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
      taskRepo
          .findById(taskRowId)
          .ifPresent(
              t -> {
                t.setInputTokens(finalIn);
                t.setOutputTokens(finalOut);
                t.setProvider(taskProvider);
                t.setModel(taskModel);
                taskRepo.save(t);
              });
    }
  }

  private static int safe(Integer value) {
    return value == null ? 0 : Math.max(0, value);
  }
}
