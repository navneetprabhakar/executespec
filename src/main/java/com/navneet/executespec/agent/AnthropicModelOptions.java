package com.navneet.executespec.agent;

import java.util.Locale;
import java.util.Set;

/** Shared Anthropic request-option rules used by native and legacy routers. */
public final class AnthropicModelOptions {

  private static final Set<String> TEMPERATURE_DEPRECATED_PREFIXES =
      Set.of("claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-5");

  private AnthropicModelOptions() {}

  public static boolean shouldOmitTemperature(String model) {
    if (model == null || model.isBlank()) return false;
    String normalized = model.toLowerCase(Locale.ROOT);
    return TEMPERATURE_DEPRECATED_PREFIXES.stream().anyMatch(normalized::startsWith);
  }

  public static boolean isTemperatureDeprecatedError(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      String msg = t.getMessage();
      if (msg == null) continue;
      String normalized = msg.toLowerCase(Locale.ROOT).replace("`", "");
      if (normalized.contains("temperature") && normalized.contains("deprecated")) return true;
    }
    return false;
  }
}
