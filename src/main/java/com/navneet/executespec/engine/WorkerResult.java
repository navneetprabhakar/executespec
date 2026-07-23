package com.navneet.executespec.engine;

import java.util.List;

import lombok.Builder;

/**
 * Output of {@link WorkerAgent#run}.
 *
 * @param outcome terminal state of the loop
 * @param iterations number of LLM calls made
 * @param toolCallsDispatched total tools dispatched across all iterations
 * @param completionSummary summary supplied via {@code completeTask} (null on non-COMPLETED
 *     outcomes)
 * @param finalAssistantText last assistant text emitted (useful when outcome is
 *     STOPPED_WITHOUT_COMPLETE)
 * @param repeatedAssistantTextCount highest count for the same normalized assistant text seen
 *     during the worker loop
 * @param toolCallLog ordered log of {tool name, opaque argument signature, safe target, ok/err} per
 *     dispatched call; raw arguments are never retained
 * @param totalLatencyMs wall-clock time across the whole loop
 * @param salvagedUnverified RR-260 — true when this result was produced by the best-guess salvage
 *     path (a single write after the iteration / stagnation / budget limit) rather than a real
 *     {@code completeTask}. That write is explicitly unverified ("a wrong-file edit beats an empty
 *     patch"), so the executor must earn promotion through the smoke gate instead of treating it as
 *     a clean completion. Defaults to false for every normal outcome.
 */
@Builder
public record WorkerResult(
    Outcome outcome,
    int iterations,
    int toolCallsDispatched,
    String completionSummary,
    String finalAssistantText,
    int repeatedAssistantTextCount,
    List<String> toolCallLog,
    long totalLatencyMs,
    boolean salvagedUnverified) {

  public enum Outcome {
    /** completeTask was called — task succeeded by the worker's own assertion. */
    COMPLETED,
    /** Model returned a response with no tool calls and no completeTask. Loop exited. */
    STOPPED_WITHOUT_COMPLETE,
    /** Iteration cap hit before completeTask was called. */
    ITERATION_LIMIT_EXCEEDED,
    /** LLM router or tool dispatch threw an unrecoverable exception. */
    ERROR,
    /** Run hit its hard cap mid-task; worker stopped cleanly. */
    BUDGET_EXCEEDED,
    /**
     * Worker made no new file writes for {@link
     * com.navneet.executespec.engine.WorkerAgent#STAGNATION_FORCE_AFTER_N} consecutive iterations
     * after a one-shot "PROGRESS CHECK" warning. The change set is presumably already in the
     * workspace (otherwise we'd see writes); the worker is just being indecisive. The smoke gate
     * downstream decides whether the change set actually compiles.
     */
    COMPLETED_BY_STAGNATION,
    /**
     * Worker exceeded its per-task wall-clock budget (see {@link
     * com.navneet.executespec.engine.WorkerTaskRequest#wallClockSeconds}). Mirrors {@link
     * #ITERATION_LIMIT_EXCEEDED} but bounded on real time instead of LLM-call count — protects
     * against expensive long-tail tasks where a slow model burns through the iter budget very fast,
     * AND against tasks where a verbose model uses too many "thin" iterations per write.
     */
    WALL_CLOCK_EXCEEDED,
    /** Shared benchmark absolute deadline reached; distinct from a relative per-task cap. */
    ABSOLUTE_DEADLINE_EXCEEDED
  }
}
