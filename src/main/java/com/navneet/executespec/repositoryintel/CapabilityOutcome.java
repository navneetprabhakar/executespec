package com.navneet.executespec.repositoryintel;

/** Typed status for one requested capability, including no-match and unavailable distinctions. */
public record CapabilityOutcome(
    RepositoryCapability capability,
    RepositoryCapabilityState state,
    String reason,
    int hitCount,
    boolean truncated) {

  public static final int MAX_REASON_CHARACTERS = 256;

  public CapabilityOutcome {
    if (capability == null) throw new IllegalArgumentException("capability is required");
    if (state == null) throw new IllegalArgumentException("state is required");
    reason = normalize(reason);
    if (reason != null && reason.length() > MAX_REASON_CHARACTERS) {
      throw new IllegalArgumentException("reason exceeds " + MAX_REASON_CHARACTERS + " characters");
    }
    if (hitCount < 0) throw new IllegalArgumentException("hitCount cannot be negative");
    if (state == RepositoryCapabilityState.AVAILABLE_HIT && hitCount == 0) {
      throw new IllegalArgumentException("AVAILABLE_HIT requires at least one hit");
    }
    if (state != RepositoryCapabilityState.AVAILABLE_HIT
        && state != RepositoryCapabilityState.PARTIAL
        && hitCount != 0) {
      throw new IllegalArgumentException(state + " cannot report hits");
    }
    if (requiresReason(state) && reason == null) {
      throw new IllegalArgumentException(state + " requires a reason");
    }
    if (truncated && state != RepositoryCapabilityState.PARTIAL) {
      throw new IllegalArgumentException("truncated outcomes must be PARTIAL");
    }
  }

  private static boolean requiresReason(RepositoryCapabilityState state) {
    return state == RepositoryCapabilityState.UNAVAILABLE
        || state == RepositoryCapabilityState.STALE_REJECTED
        || state == RepositoryCapabilityState.PARTIAL
        || state == RepositoryCapabilityState.FAILED;
  }

  private static String normalize(String value) {
    String cleaned = RepositoryIntelligenceIds.clean(value);
    return cleaned == null || cleaned.isEmpty() ? null : cleaned;
  }
}
