package com.navneet.executespec.v2.agent;

import lombok.Builder;

import java.util.List;

/**
 * Output of {@link WorkerAgentV2#run}.
 *
 * @param outcome              terminal state of the loop
 * @param iterations           number of LLM calls made
 * @param toolCallsDispatched  total tools dispatched across all iterations
 * @param completionSummary    summary supplied via {@code completeTask} (null on non-COMPLETED outcomes)
 * @param finalAssistantText   last assistant text emitted (useful when outcome is STOPPED_WITHOUT_COMPLETE)
 * @param toolCallLog          ordered log of {tool name, args preview, ok/err} per dispatched call
 * @param totalLatencyMs       wall-clock time across the whole loop
 */
@Builder
public record WorkerResult(
        Outcome outcome,
        int iterations,
        int toolCallsDispatched,
        String completionSummary,
        String finalAssistantText,
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
        ERROR
    }
}
