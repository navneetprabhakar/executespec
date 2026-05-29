package com.navneet.executespec.engine;

/**
 * Token + dollar cost of a {@link CodeGenEngine#solve} (cache-aware). Phase 2's facade fills this
 * from {@code RunCostTracker}.
 *
 * @param inputTokens total input tokens
 * @param outputTokens total output tokens
 * @param cacheReadTokens cached input tokens (subset of input, priced lower)
 * @param usd dollar cost at the per-model rate
 */
public record EngineCost(long inputTokens, long outputTokens, long cacheReadTokens, double usd) {

  public static EngineCost zero() {
    return new EngineCost(0L, 0L, 0L, 0.0);
  }
}
