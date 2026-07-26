package com.navneet.executespec.integration;

import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.RunModelOverrides;

/** Resolves a managed subscription tier's provider/model lock for run-scoped agent routing. */
final class ManagedTierRouteLock {

  private ManagedTierRouteLock() {}

  record Binding(LlmProvider provider, String model, boolean locked) {}

  static Binding resolve(
      ManagedAiProperties properties,
      ModelRegistry modelRegistry,
      String ownerTier,
      LlmProvider fallbackProvider,
      String fallbackModel) {
    ManagedAiProperties.TierPolicy policy = properties.policyFor(ownerTier);
    if (policy.getLockedProvider() == null
        || policy.getLockedProvider().isBlank()
        || policy.getLockedModel() == null
        || policy.getLockedModel().isBlank()) {
      return new Binding(fallbackProvider, fallbackModel, false);
    }
    LlmProvider provider =
        LlmProvider.valueOf(policy.getLockedProvider().trim().toUpperCase(Locale.ROOT));
    String model = modelRegistry.canonicalize(policy.getLockedModel().trim());
    return new Binding(provider, model, true);
  }

  static Map<String, String> apply(
      ManagedAiProperties properties,
      ModelRegistry modelRegistry,
      String ownerTier,
      Map<String, String> orgOverrides,
      LlmProvider fallbackProvider,
      String fallbackModel) {
    Binding binding =
        resolve(properties, modelRegistry, ownerTier, fallbackProvider, fallbackModel);
    return RunModelOverrides.withRunPin(orgOverrides, binding.provider(), binding.model());
  }
}
