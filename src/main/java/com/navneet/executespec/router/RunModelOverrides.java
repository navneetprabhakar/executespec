package com.navneet.executespec.router;

import java.util.Map;

/**
 * Run-scoped per-org model overrides (F3). The pipeline resolves the org's override map once at run
 * start and binds it to the executing thread; {@link ModelResolver#resolve} then consults it so
 * every tier-routed agent (worker, planner, reviewer, …) honours the override without threading an
 * orgId through ~15 call sites.
 *
 * <p>Safe by construction: the worker, planner, and reviewer all resolve their model synchronously
 * on the pipeline thread (the worker's model is picked in {@code SequentialExecutor.tierConfigFor}
 * before the worker is dispatched), so binding here covers them. Any thread without a binding (or
 * any unset cell) falls through to the {@code application.yml} default — an absent override is
 * never an error, only the YAML default.
 *
 * <p>Keys are {@code "<provider>:<tier>"} lowercase, e.g. {@code "anthropic:planning"}.
 */
public final class RunModelOverrides {

  private static final ThreadLocal<Map<String, String>> CTX = new ThreadLocal<>();

  private RunModelOverrides() {}

  /** Builds the canonical override-map key for a (provider, tier) cell. */
  public static String key(LlmProvider provider, AgentTier tier) {
    return provider.name().toLowerCase() + ":" + tier.name().toLowerCase();
  }

  /**
   * Binds the override map to the current thread. A null/empty map clears the binding so resolution
   * falls straight through to YAML.
   */
  public static void bind(Map<String, String> overrides) {
    if (overrides == null || overrides.isEmpty()) {
      CTX.remove();
    } else {
      CTX.set(Map.copyOf(overrides));
    }
  }

  /** Clears the current thread's binding. Always call in a {@code finally} to avoid leakage. */
  public static void clear() {
    CTX.remove();
  }

  /** Returns the overriding model id for this cell, or null when there is no override. */
  public static String lookup(LlmProvider provider, AgentTier tier) {
    Map<String, String> m = CTX.get();
    if (m == null) {
      return null;
    }
    String model = m.get(key(provider, tier));
    return (model != null && !model.isBlank()) ? model : null;
  }
}
