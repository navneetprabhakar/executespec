package com.navneet.executespec.harness;

/**
 * Immutable decision produced by {@link TaskCompileSupervisor} after classifying
 * post-merge compile errors for a single task or task group.
 */
public record TaskCompileDecision(
        SupervisionAction action,
        int totalErrors,
        int ownedErrorCount,
        int cascadingErrorCount,
        /** 0-100: how fixable are the owned errors without re-running the worker */
        int fixabilityScore,
        /** 1-2 sentence diagnosis of what went wrong */
        String assessment,
        /** Staff-engineer reasoning for the chosen action */
        String rationale,
        /** Patch instructions — non-null only when action == AUTO_FIX */
        String autoFixInstructions,
        /** Targeted feedback for worker retry — non-null only when action == RETRY_TASK */
        String retryInstructions
) {

    /**
     * Produces a new decision escalated to RETRY_TASK from an AUTO_FIX that failed.
     * Preserves all classification counts and scores.
     */
    public TaskCompileDecision escalateToRetry(String newRetryInstructions) {
        return new TaskCompileDecision(
                SupervisionAction.RETRY_TASK,
                totalErrors, ownedErrorCount, cascadingErrorCount,
                fixabilityScore, assessment, rationale,
                null, newRetryInstructions
        );
    }
}
