package com.navneet.executespec.executioncontext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable, secret-free provider/model/key-source policy captured when an LLM context is created.
 * Actual provider credentials are resolved at dispatch time and are never stored in this value.
 */
public record LlmPolicySnapshot(
    KeySourceMode keySourceMode,
    String keySubjectUserId,
    Set<String> allowedProviders,
    Map<String, List<String>> allowedProviderModels,
    Map<String, CredentialSource> providerKeySources,
    Map<String, String> modelOverrides,
    String requestedProvider,
    String requestedModel,
    RoutingMode routingMode,
    boolean routePinned,
    boolean allowProviderFailover,
    int routeSnapshotVersion) {

  public enum KeySourceMode {
    SYSTEM,
    OWN_KEYS,
    HYBRID
  }

  public enum CredentialSource {
    USER,
    ORG,
    PLATFORM
  }

  public enum RoutingMode {
    PROVIDER_PREFERRED,
    EXACT
  }

  public LlmPolicySnapshot {
    if (keySourceMode == null) throw new IllegalArgumentException("keySourceMode required");
    if (routingMode == null) throw new IllegalArgumentException("routingMode required");
    if (routeSnapshotVersion <= 0) {
      throw new IllegalArgumentException("routeSnapshotVersion must be positive");
    }

    keySubjectUserId = trimToNull(keySubjectUserId);
    allowedProviders = normalizeProviders(allowedProviders);
    allowedProviderModels = normalizeModels(allowedProviderModels);
    providerKeySources = normalizeSources(providerKeySources);
    modelOverrides = normalizeOverrides(modelOverrides);
    requestedProvider = normalizeProvider(requestedProvider);
    requestedModel = trimToNull(requestedModel);

    if (allowedProviders.isEmpty()) {
      throw new IllegalArgumentException("at least one allowed provider is required");
    }
    if (!allowedProviders.containsAll(providerKeySources.keySet())
        || !providerKeySources.keySet().containsAll(allowedProviders)) {
      throw new IllegalArgumentException(
          "providerKeySources must contain exactly the allowed providers");
    }
    if (!allowedProviders.containsAll(allowedProviderModels.keySet())) {
      throw new IllegalArgumentException("model policy contains a disallowed provider");
    }
    if (!allowedProviders.containsAll(modelOverrides.keySet())) {
      throw new IllegalArgumentException("model override contains a disallowed provider");
    }

    for (String provider : allowedProviders) {
      CredentialSource source = providerKeySources.get(provider);
      if (keySourceMode == KeySourceMode.OWN_KEYS && source == CredentialSource.PLATFORM) {
        throw new IllegalArgumentException(
            "OWN_KEYS policy cannot allow PLATFORM credentials for " + provider);
      }
      if (keySourceMode == KeySourceMode.SYSTEM && source != CredentialSource.PLATFORM) {
        throw new IllegalArgumentException(
            "SYSTEM policy requires PLATFORM credentials for " + provider);
      }
    }

    if (requestedProvider != null && !allowedProviders.contains(requestedProvider)) {
      throw new IllegalArgumentException("requested provider is not allowed: " + requestedProvider);
    }
    if (requestedModel != null && requestedProvider == null) {
      throw new IllegalArgumentException("requested model requires requested provider");
    }
    if (requestedModel != null) {
      List<String> allowedModels = allowedProviderModels.get(requestedProvider);
      if (allowedModels != null
          && !allowedModels.isEmpty()
          && !allowedModels.contains(requestedModel)) {
        throw new IllegalArgumentException("requested model is outside the provider allow-list");
      }
    }
    for (Map.Entry<String, String> entry : modelOverrides.entrySet()) {
      List<String> allowedModels = allowedProviderModels.get(entry.getKey());
      if (allowedModels != null
          && !allowedModels.isEmpty()
          && !allowedModels.contains(entry.getValue())) {
        throw new IllegalArgumentException(
            "model override is outside the provider allow-list: " + entry.getKey());
      }
    }

    if (routePinned) {
      if (routingMode != RoutingMode.EXACT || requestedProvider == null || requestedModel == null) {
        throw new IllegalArgumentException(
            "pinned route requires EXACT mode plus requested provider and model");
      }
      if (allowProviderFailover) {
        throw new IllegalArgumentException("pinned route cannot allow provider failover");
      }
    }
  }

  private static Set<String> normalizeProviders(Set<String> providers) {
    TreeSet<String> normalized = new TreeSet<>();
    if (providers != null) {
      for (String provider : providers) {
        String value = normalizeProvider(provider);
        if (value != null) normalized.add(value);
      }
    }
    return Collections.unmodifiableSet(normalized);
  }

  private static Map<String, List<String>> normalizeModels(
      Map<String, List<String>> providerModels) {
    TreeMap<String, List<String>> normalized = new TreeMap<>();
    if (providerModels != null) {
      for (Map.Entry<String, List<String>> entry : providerModels.entrySet()) {
        String provider = normalizeProvider(entry.getKey());
        if (provider == null) continue;
        TreeSet<String> models = new TreeSet<>();
        if (entry.getValue() != null) {
          for (String model : entry.getValue()) {
            String value = trimToNull(model);
            if (value != null) models.add(value);
          }
        }
        normalized.put(provider, List.copyOf(models));
      }
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
  }

  private static Map<String, CredentialSource> normalizeSources(
      Map<String, CredentialSource> sources) {
    TreeMap<String, CredentialSource> normalized = new TreeMap<>();
    if (sources != null) {
      for (Map.Entry<String, CredentialSource> entry : sources.entrySet()) {
        String provider = normalizeProvider(entry.getKey());
        if (provider == null || entry.getValue() == null) {
          throw new IllegalArgumentException("provider key source cannot be blank");
        }
        normalized.put(provider, entry.getValue());
      }
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
  }

  private static Map<String, String> normalizeOverrides(Map<String, String> overrides) {
    TreeMap<String, String> normalized = new TreeMap<>();
    if (overrides != null) {
      for (Map.Entry<String, String> entry : overrides.entrySet()) {
        String provider = normalizeProvider(entry.getKey());
        String model = trimToNull(entry.getValue());
        if (provider == null || model == null) {
          throw new IllegalArgumentException("model override cannot be blank");
        }
        normalized.put(provider, model);
      }
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
  }

  private static String normalizeProvider(String provider) {
    String normalized = trimToNull(provider);
    if (normalized == null) return null;
    normalized = normalized.toLowerCase(Locale.ROOT);
    return "google".equals(normalized) ? "gemini" : normalized;
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  /** Stable secret-free representation used in idempotency fingerprints. */
  String canonicalForm() {
    List<String> sourceRows = new ArrayList<>();
    providerKeySources.forEach((provider, source) -> sourceRows.add(provider + "=" + source));
    return String.join(
        "|",
        keySourceMode.name(),
        String.valueOf(keySubjectUserId),
        allowedProviders.toString(),
        allowedProviderModels.toString(),
        sourceRows.toString(),
        modelOverrides.toString(),
        String.valueOf(requestedProvider),
        String.valueOf(requestedModel),
        routingMode.name(),
        String.valueOf(routePinned),
        String.valueOf(allowProviderFailover),
        String.valueOf(routeSnapshotVersion));
  }
}
