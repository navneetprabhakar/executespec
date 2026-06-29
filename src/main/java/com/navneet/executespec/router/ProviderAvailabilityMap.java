package com.navneet.executespec.router;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Utility for turning resolved provider availability into candidate-service inputs. */
public final class ProviderAvailabilityMap {

  private ProviderAvailabilityMap() {}

  public static Map<String, Boolean> fromAvailableProviders(
      Set<LlmProvider> availableProviders, Iterable<String> knownProviderNames) {
    if (availableProviders == null || availableProviders.isEmpty()) {
      return Map.of();
    }

    Set<String> availableAliases = new LinkedHashSet<>();
    availableProviders.forEach(provider -> availableAliases.addAll(aliases(provider)));

    Map<String, Boolean> out = new LinkedHashMap<>();
    if (knownProviderNames != null) {
      for (String provider : knownProviderNames) {
        if (provider != null && !provider.isBlank()) {
          out.put(provider, availableAliases.contains(normalize(provider)));
        }
      }
    }
    availableAliases.forEach(alias -> out.putIfAbsent(alias, Boolean.TRUE));
    return Map.copyOf(out);
  }

  private static Set<String> aliases(LlmProvider provider) {
    if (provider == null) {
      return Set.of();
    }
    return switch (provider) {
      case ANTHROPIC -> Set.of("anthropic");
      case OPENAI -> Set.of("openai");
      case GEMINI -> Set.of("gemini", "google");
      case OPENROUTER -> Set.of("openrouter");
      case DEEPSEEK -> Set.of("deepseek");
      case FIREWORKS -> Set.of("fireworks");
      case SARVAM -> Set.of("sarvam");
    };
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
