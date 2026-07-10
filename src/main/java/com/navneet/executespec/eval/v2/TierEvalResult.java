package com.navneet.executespec.eval.v2;

import com.navneet.executespec.router.AgentTier;

import lombok.Builder;

/**
 * One tier-eval call result. Shaped around what production actually cares about: tool-call
 * validity, cache hit rate, latency, and cost — not just text content matches.
 *
 * <p>{@link #correctnessScore} is 0–100 from the case's validator. 100 means "the model produced
 * the expected output for this case." {@link #failureMode} captures runtime failures that prevented
 * the validator from running at all; in that case correctnessScore is 0 and the detail string
 * carries the error.
 *
 * <p>Cache stats: {@link #cacheReadTokens} is the count of input tokens that hit the provider's
 * prompt cache (Anthropic cache_read_input_tokens, Gemini cachedContentTokenCount, OpenAI
 * cached_tokens). The cache-hit ratio {@link #cacheHitRatio} expresses cacheRead / (inputTokens +
 * cacheReadTokens) so 0.0 = no cache hit, 1.0 = whole prompt cached.
 */
@Builder
public record TierEvalResult(
    String caseName,
    AgentTier tier,
    String provider,
    String model,
    long latencyMs,
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    double costUsd,
    int toolCallsAttempted,
    int toolCallsMalformed,
    String firstToolCallName,
    int iterationsUsed,
    int correctnessScore,
    TierEvalFailureMode failureMode,
    String detail,
    String promptFingerprint) {

  public double cacheHitRatio() {
    long total = inputTokens + cacheReadTokens;
    return total == 0 ? 0.0 : (double) cacheReadTokens / (double) total;
  }
}
