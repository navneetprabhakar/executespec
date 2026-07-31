package com.navneet.executespec.harness;

import com.navneet.executespec.router.AgentTier;

/** Immutable worker route and mechanical budget selected for one task attempt. */
final class TierConfig {
  final String provider;
  final String model;
  final AgentTier tier;
  final int maxIter;
  final int maxTokens;
  final int stagnationWarnAfter;
  final int stagnationForceAfter;
  final int wallClockSeconds;
  final boolean forceToolChoiceOnSalvage;

  TierConfig(
      String provider,
      String model,
      AgentTier tier,
      int maxIter,
      int maxTokens,
      int stagnationWarnAfter,
      int stagnationForceAfter,
      int wallClockSeconds,
      boolean forceToolChoiceOnSalvage) {
    this.provider = provider;
    this.model = model;
    this.tier = tier;
    this.maxIter = maxIter;
    this.maxTokens = maxTokens;
    this.stagnationWarnAfter = stagnationWarnAfter;
    this.stagnationForceAfter = stagnationForceAfter;
    this.wallClockSeconds = wallClockSeconds;
    this.forceToolChoiceOnSalvage = forceToolChoiceOnSalvage;
  }

  String provider() {
    return provider;
  }

  String model() {
    return model;
  }

  AgentTier tier() {
    return tier;
  }

  int maxIter() {
    return maxIter;
  }

  int maxTokens() {
    return maxTokens;
  }

  int stagnationWarnAfter() {
    return stagnationWarnAfter;
  }

  int stagnationForceAfter() {
    return stagnationForceAfter;
  }

  int wallClockSeconds() {
    return wallClockSeconds;
  }

  boolean forceToolChoiceOnSalvage() {
    return forceToolChoiceOnSalvage;
  }

  TierConfig withProviderModel(String provider, String model) {
    return new TierConfig(
        provider,
        model,
        tier,
        maxIter,
        maxTokens,
        stagnationWarnAfter,
        stagnationForceAfter,
        wallClockSeconds,
        forceToolChoiceOnSalvage);
  }
}
