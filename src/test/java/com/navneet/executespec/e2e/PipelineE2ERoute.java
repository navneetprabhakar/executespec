package com.navneet.executespec.e2e;

import java.util.Locale;
import java.util.Map;

/**
 * Explicit bounded live-fixture routes; production routing and transport authorization still apply.
 */
record PipelineE2ERoute(String provider, String model) {
  private static final Map<String, java.util.List<String>> MODELS =
      Map.of(
          "DEEPSEEK", java.util.List.of("deepseek-flash-native", "deepseek-v4-pro-native"),
          "ANTHROPIC", java.util.List.of("claude-haiku-5-5", "claude-sonnet-5-5"),
          "OPENAI", java.util.List.of("gpt-6-luna", "gpt-6.1-sol"),
          "GEMINI", java.util.List.of("gemini-3.8-flash", "gemini-4-argon"),
          "OPENROUTER", java.util.List.of("deepseek-v4.1-flash-1017", "gemini-3.8-flash-or"),
          "FIREWORKS", java.util.List.of("deepseek-v4p1-flash-1017-fw", "glm-5p3-flash-fw"));

  static PipelineE2ERoute from(Map<String, String> environment) {
    String provider = environment.getOrDefault("E2E_PROVIDER", "DEEPSEEK").toUpperCase(Locale.ROOT);
    var models = MODELS.get(provider);
    if (models == null) {
      throw new IllegalArgumentException("Unsupported E2E provider");
    }
    String model = environment.getOrDefault("E2E_MODEL", models.getFirst());
    if (!models.contains(model)) {
      throw new IllegalArgumentException("E2E model must match the bounded provider route");
    }
    return new PipelineE2ERoute(provider, model);
  }

  static double costCap(Map<String, String> environment) {
    double cap = Double.parseDouble(environment.getOrDefault("E2E_COST_CAP_USD", "0.10"));
    if (!Double.isFinite(cap)
        || cap < com.navneet.executespec.cost.CostCapValues.MIN_USD
        || cap > 0.50) {
      throw new IllegalArgumentException("E2E cost cap must be between 0.10 and 0.50 USD");
    }
    return cap;
  }
}
