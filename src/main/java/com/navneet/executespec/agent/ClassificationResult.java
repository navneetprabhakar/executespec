package com.navneet.executespec.agent;

/**
 * Output of {@link SmartClassifierAgent}. Captures the chosen strategy, size estimates, the source
 * path that produced it (so we can calibrate heuristic vs LLM decisions later), and the cost we
 * paid to compute it.
 *
 * @param strategy chosen planner strategy
 * @param estimatedTaskCount classifier's best guess at how many top-level tasks the outline will
 *     emit
 * @param estimatedRepoTouchCount how many repos the work likely spans
 * @param requiresEpics hint to the outline planner that some tasks should be marked EPIC
 * @param reasoning one-line rationale (persisted on pipeline_run.classifier_reasoning)
 * @param source HEURISTIC_FAST_PATH | LLM_VERIFIED | LLM_FALLBACK_TO_HEURISTIC
 * @param inputTokens classifier-call input tokens (0 for fast path)
 * @param outputTokens classifier-call output tokens (0 for fast path)
 * @param costUsd classifier-call cost in USD (0 for fast path)
 */
public record ClassificationResult(
    PlanStrategy strategy,
    int estimatedTaskCount,
    int estimatedRepoTouchCount,
    boolean requiresEpics,
    String reasoning,
    Source source,
    long inputTokens,
    long outputTokens,
    double costUsd) {
  public enum Source {
    /** Heuristic alone matched the obvious-tiny pattern; no LLM call fired. */
    HEURISTIC_FAST_PATH,
    /** LLM verifier ran and we used its verdict. */
    LLM_VERIFIED,
    /**
     * LLM call failed (timeout / parse error / enum mismatch); we fell back to the heuristic
     * verdict.
     */
    LLM_FALLBACK_TO_HEURISTIC
  }
}
