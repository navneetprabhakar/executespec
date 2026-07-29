package com.navneet.executespec.repositoryintel;

/** Hard, deterministic bounds applied across repository-intelligence adapters. */
public record QueryBudget(
    int maxAdapterCalls,
    int maxHitsPerCapability,
    int maxTotalHits,
    int maxCharacters,
    int maxEstimatedTokens,
    long timeoutMillis) {

  public static final QueryBudget DEFAULT = new QueryBudget(6, 50, 200, 60_000, 15_000, 5_000);

  public QueryBudget {
    requirePositive("maxAdapterCalls", maxAdapterCalls);
    requirePositive("maxHitsPerCapability", maxHitsPerCapability);
    requirePositive("maxTotalHits", maxTotalHits);
    requirePositive("maxCharacters", maxCharacters);
    requirePositive("maxEstimatedTokens", maxEstimatedTokens);
    if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
    if (maxHitsPerCapability > maxTotalHits) {
      throw new IllegalArgumentException("maxHitsPerCapability cannot exceed maxTotalHits");
    }
  }

  public String stableKey() {
    return maxAdapterCalls
        + ":"
        + maxHitsPerCapability
        + ":"
        + maxTotalHits
        + ":"
        + maxCharacters
        + ":"
        + maxEstimatedTokens
        + ":"
        + timeoutMillis;
  }

  private static void requirePositive(String name, int value) {
    if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
  }
}
