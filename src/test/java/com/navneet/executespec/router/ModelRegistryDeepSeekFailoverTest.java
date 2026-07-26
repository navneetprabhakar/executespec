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
    props.setFailoverOrder(Map.of("deepseek", List.of("openrouter", "fireworks")));
    props.setModels(
        Map.of(
            "deepseek-v4-pro-native",
            model("CAPABLE"),
            "deepseek-v4-flash-native",
            model("UTILITY")));

    ModelRegistry registry = new ModelRegistry(props);

    assertThat(registry.fallbackChainFor("deepseek-v4-pro-native", "deepseek"))
        .containsExactly(
            new String[] {"openrouter", "or-pro"}, new String[] {"fireworks", "fw-pro"});
    assertThat(registry.fallbackChainFor("deepseek-v4-flash-native", "deepseek"))
        .containsExactly(
            new String[] {"openrouter", "or-flash"}, new String[] {"fireworks", "fw-flash"});
  }

  @Test
  void configuredProviderOrderWinsOverGenericTierChainOrder() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("model", model("CAPABLE")));
    props.setProviders(
        Map.of(
            "anthropic", provider("claude-plan", "claude-work", "claude-light"),
            "gemini", provider("gem-plan", "gem-work", "gem-light")));
    props.setFailoverOrder(Map.of("openai", List.of("gemini", "anthropic")));
    props.setFallbackChains(
        Map.of("CAPABLE", List.of(fallback("anthropic", "claude"), fallback("openai", "gpt"))));

    ModelRegistry registry = new ModelRegistry(props);

    assertThat(registry.fallbackChainFor("model", "openai"))
        .containsExactly(
            new String[] {"gemini", "gem-work"}, new String[] {"anthropic", "claude-work"});
  }

  @Test
  void providerWithoutConfiguredFailoverDoesNotInheritGenericChain() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("model", model("CAPABLE")));
    props.setFallbackChains(
        Map.of("CAPABLE", List.of(fallback("anthropic", "claude"), fallback("openai", "gpt"))));

    ModelRegistry registry = new ModelRegistry(props);

    assertThat(registry.fallbackChainFor("model", "fireworks")).isEmpty();
  }

  @Test
  void utilityFailoverUsesEachTargetsLightweightModel() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("cheap-primary", model("UTILITY")));
    props.setProviders(
        Map.of(
            "gemini", provider("gem-plan", "gem-work", "gem-light"),
            "anthropic", provider("claude-plan", "claude-work", "claude-light")));
    props.setFailoverOrder(Map.of("openai", List.of("gemini", "anthropic")));

    ModelRegistry registry = new ModelRegistry(props);

    assertThat(registry.fallbackChainFor("cheap-primary", "openai"))
        .containsExactly(
            new String[] {"gemini", "gem-light"}, new String[] {"anthropic", "claude-light"});
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
