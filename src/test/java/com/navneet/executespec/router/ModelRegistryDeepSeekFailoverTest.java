package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelRegistryDeepSeekFailoverTest {

  @Test
  void deepSeekUsesNativeThenOpenRouterThenFireworksFromProviderMatrix() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setProviders(
        Map.of(
            "openrouter", provider("or-pro", "or-pro", "or-flash"),
            "fireworks", provider("fw-pro", "fw-pro", "fw-flash")));

    ModelRegistry registry = new ModelRegistry(props);

    assertThat(registry.fallbackChainFor("deepseek-v4-pro-native", "deepseek"))
        .containsExactly(
            new String[] {"openrouter", "or-pro"}, new String[] {"fireworks", "fw-pro"});
    assertThat(registry.fallbackChainFor("deepseek-v4-flash-native", "deepseek"))
        .containsExactly(
            new String[] {"openrouter", "or-flash"}, new String[] {"fireworks", "fw-flash"});
  }

  @Test
  void nonDeepSeekProvidersKeepTheExistingTierFallbackChain() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("model", model("CAPABLE")));
    props.setFallbackChains(
        Map.of("CAPABLE", List.of(fallback("anthropic", "claude"), fallback("openai", "gpt"))));

    ModelRegistry registry = new ModelRegistry(props);

    assertThat(registry.fallbackChainFor("model", "openai"))
        .containsExactly(new String[] {"anthropic", "claude"}, new String[] {"openai", "gpt"});
  }

  private static ModelMatrixProperties.ProviderModels provider(
      String planning, String workhorse, String lightweight) {
    ModelMatrixProperties.ProviderModels models = new ModelMatrixProperties.ProviderModels();
    models.setPlanning(planning);
    models.setWorkhorse(workhorse);
    models.setLightweight(lightweight);
    return models;
  }

  private static ModelMatrixProperties.ModelConfig model(String tier) {
    ModelMatrixProperties.ModelConfig config = new ModelMatrixProperties.ModelConfig();
    config.setChainTier(tier);
    return config;
  }

  private static ModelMatrixProperties.FallbackEntry fallback(String provider, String model) {
    ModelMatrixProperties.FallbackEntry entry = new ModelMatrixProperties.FallbackEntry();
    entry.setProvider(provider);
    entry.setModel(model);
    return entry;
  }
}
