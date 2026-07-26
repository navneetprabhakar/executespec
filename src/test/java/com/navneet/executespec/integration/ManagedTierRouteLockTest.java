package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.Test;

class ManagedTierRouteLockTest {

  @Test
  void freeTierLockOverridesTheFallbackRouteForEveryAgentTierBinding() {
    ManagedAiProperties properties = new ManagedAiProperties();
    ManagedAiProperties.TierPolicy free = new ManagedAiProperties.TierPolicy();
    free.setLockedProvider("deepseek");
    free.setLockedModel("deepseek-v4-flash-native");
    properties.setTiers(Map.of("FREE", free));
    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.canonicalize("deepseek-v4-flash-native")).thenReturn("deepseek-v4-flash-native");

    ManagedTierRouteLock.Binding result =
        ManagedTierRouteLock.resolve(
            properties, registry, "FREE", LlmProvider.DEEPSEEK, "deepseek-v4-pro-native");

    assertThat(result.locked()).isTrue();
    assertThat(result.provider()).isEqualTo(LlmProvider.DEEPSEEK);
    assertThat(result.model()).isEqualTo("deepseek-v4-flash-native");
    assertThat(
            ManagedTierRouteLock.apply(
                properties,
                registry,
                "FREE",
                Map.of("deepseek:planning", "deepseek-v4-pro-native"),
                LlmProvider.DEEPSEEK,
                "deepseek-v4-pro-native"))
        .containsEntry("deepseek:planning", "deepseek-v4-flash-native")
        .containsEntry("deepseek:workhorse", "deepseek-v4-flash-native")
        .containsEntry("deepseek:lightweight", "deepseek-v4-flash-native");
  }

  @Test
  void unlockedTierPreservesTheValidatedFallbackRoute() {
    ManagedAiProperties properties = new ManagedAiProperties();
    properties.setTiers(Map.of("PRO", new ManagedAiProperties.TierPolicy()));

    ManagedTierRouteLock.Binding result =
        ManagedTierRouteLock.resolve(
            properties, mock(ModelRegistry.class), "PRO", LlmProvider.OPENAI, "gpt-5.4-mini");

    assertThat(result.locked()).isFalse();
    assertThat(result.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(result.model()).isEqualTo("gpt-5.4-mini");
  }
}
