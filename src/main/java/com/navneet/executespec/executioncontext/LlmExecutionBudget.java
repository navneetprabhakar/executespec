package com.navneet.executespec.executioncontext;

import java.math.BigDecimal;

/** Optional hard ceilings attached to one durable LLM execution context. */
public record LlmExecutionBudget(
    Long maxInputTokens,
    Long maxOutputTokens,
    Long maxTotalTokens,
    BigDecimal maxCostUsd,
    Integer maxProviderCalls) {

  public static final LlmExecutionBudget UNBOUNDED =
      new LlmExecutionBudget(null, null, null, null, null);

  public LlmExecutionBudget {
    requirePositive(maxInputTokens, "maxInputTokens");
    requirePositive(maxOutputTokens, "maxOutputTokens");
    requirePositive(maxTotalTokens, "maxTotalTokens");
    requirePositive(maxProviderCalls, "maxProviderCalls");
    if (maxCostUsd != null && maxCostUsd.signum() <= 0) {
      throw new IllegalArgumentException("maxCostUsd must be positive when present");
    }
    if (maxCostUsd != null) maxCostUsd = maxCostUsd.stripTrailingZeros();
  }

  private static void requirePositive(Number value, String field) {
    if (value != null && value.longValue() <= 0) {
      throw new IllegalArgumentException(field + " must be positive when present");
    }
  }
}
