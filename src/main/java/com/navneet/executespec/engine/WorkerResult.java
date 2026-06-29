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
 * @param toolCallLog ordered log of {tool name, args preview, ok/err} per dispatched call
 * @param totalLatencyMs wall-clock time across the whole loop
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
    long totalLatencyMs) {

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
    WALL_CLOCK_EXCEEDED
  }
}
