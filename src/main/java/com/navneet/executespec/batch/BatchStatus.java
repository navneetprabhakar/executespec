package com.navneet.executespec.batch;

/**
 * Provider-agnostic batch status — normalized from each provider's native status.
 *
 * @param state             Unified batch state
 * @param completedRequests Number of requests that have finished
 * @param totalRequests     Total requests in the batch
 * @param error             Error message (null unless state is FAILED)
 */
public record BatchStatus(
        State state,
        int completedRequests,
        int totalRequests,
        String error
) {
    public enum State {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        EXPIRED,
        CANCELING
    }
}
