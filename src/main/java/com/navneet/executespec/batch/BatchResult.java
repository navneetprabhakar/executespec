package com.navneet.executespec.batch;

/**
 * A single task result from a completed batch.
 *
 * @param customId     Maps back to the task ID from {@link BatchRequest#customId()}
 * @param content      Generated text output from the LLM
 * @param inputTokens  Input tokens consumed
 * @param outputTokens Output tokens consumed
 * @param stopReason   Why the LLM stopped (e.g. "end_turn", "max_tokens", "stop")
 */
public record BatchResult(
        String customId,
        String content,
        int inputTokens,
        int outputTokens,
        String stopReason
) {}
