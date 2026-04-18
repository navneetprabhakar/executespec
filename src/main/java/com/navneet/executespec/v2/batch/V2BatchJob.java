package com.navneet.executespec.v2.batch;

import com.navneet.executespec.router.LlmProvider;

import java.time.Instant;

/**
 * In-flight batch submission. See {@code docs/plans/v2-batch-execution-plan.md} §3.
 *
 * <p>Persisted alongside {@code PipelineRun.batchProviderJobId} / {@code batch*}
 * fields for crash recovery. The {@link #providerJobId} maps to the upstream
 * batch resource (Anthropic message batch, OpenAI batch ID, Gemini long-running
 * operation name).
 */
public record V2BatchJob(
        String runId,
        LlmProvider provider,
        String providerJobId,
        V2BatchJobState state,
        Instant submittedAt,
        Instant lastPolledAt) {

    public enum V2BatchJobState {
        SUBMITTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
