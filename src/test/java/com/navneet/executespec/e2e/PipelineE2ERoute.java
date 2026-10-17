package com.navneet.executespec.e2e;

import java.util.Locale;
import java.util.Map;

/**
 * Explicit bounded live-fixture routes; production routing and transport authorization still apply.
 */
record PipelineE2ERoute(String provider, String model) {
  private static final Map<String, String> MODELS =
      Map.of(
          "DEEPSEEK", "deepseek-v4-flash-native",
          "ANTHROPIC", "claude-haiku-4-5-20251001",
          "OPENAI", "gpt-5.4-mini",
          "GEMINI", "gemini-3.5-flash-lite",
          "OPENROUTER", "deepseek-v4-flash",
          "FIREWORKS", "deepseek-v4p1-flash-fw");

  static PipelineE2ERoute from(Map<String, String> environment) {
    String provider = environment.getOrDefault("E2E_PROVIDER", "DEEPSEEK").toUpperCase(Locale.ROOT);
    String expectedModel = MODELS.get(provider);
    if (expectedModel == null) {
      throw new IllegalArgumentException("Unsupported E2E provider");
    }
    String model = environment.getOrDefault("E2E_MODEL", expectedModel);
    if (!expectedModel.equals(model)) {
      throw new IllegalArgumentException("E2E model must match the bounded provider route");
    }
    return new PipelineE2ERoute(provider, model);
  }

  static double costCap(Map<String, String> environment) {
    double cap = Double.parseDouble(environment.getOrDefault("E2E_COST_CAP_USD", "0.10"));
    if (!Double.isFinite(cap) || cap <= 0 || cap > 0.50) {
      throw new IllegalArgumentException("E2E cost cap must be positive and at most 0.50 USD");
    }
    return cap;
  }
}
