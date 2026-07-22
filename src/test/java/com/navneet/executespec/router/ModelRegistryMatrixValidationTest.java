package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * RR-114 (2026-07-22) — an invalid model matrix must fail startup, not merely log and continue. All
 * violations are aggregated into one readiness-failing exception so production never becomes ready
 * on a partially broken routing/pricing matrix.
 */
class ModelRegistryMatrixValidationTest {

  private static ModelMatrixProperties.ModelConfig model(String provider) {
    ModelMatrixProperties.ModelConfig c = new ModelMatrixProperties.ModelConfig();
    c.setProvider(provider);
    return c;
  }

  private static ModelMatrixProperties.FreeTierConfig freeTier(String provider, String modelName) {
    ModelMatrixProperties.FreeTierConfig f = new ModelMatrixProperties.FreeTierConfig();
    f.setProvider(provider);
    f.setModel(modelName);
    return f;
  }

  private static ModelMatrixProperties.ProviderModels providerModels(
      String planning, String workhorse, String lightweight) {
    ModelMatrixProperties.ProviderModels pm = new ModelMatrixProperties.ProviderModels();
    pm.setPlanning(planning);
    pm.setWorkhorse(workhorse);
    pm.setLightweight(lightweight);
    return pm;
  }

  @Test
  void validate_throws_whenProviderReferencesUnknownModel() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("known-model", model("openai")));
    // planning references a model that does not exist in the models map.
    props.setProviders(
        Map.of("openai", providerModels("missing-planning-model", "known-model", "known-model")));
    props.setFreeTier(freeTier("openai", "known-model"));

    ModelRegistry registry = new ModelRegistry(props);

    assertThatThrownBy(registry::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RR-114")
        .hasMessageContaining("missing-planning-model");
  }

  @Test
  void validate_aggregatesAllViolations_notJustTheFirst() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("known-model", model("openai")));
    props.setProviders(
        Map.of("openai", providerModels("bad-planning", "bad-workhorse", "known-model")));
    Map<String, String> aliases = new LinkedHashMap<>();
    aliases.put("legacy", "unknown-target");
    props.setLegacyAliases(aliases);
    props.setFreeTier(freeTier("openai", "known-model"));

    ModelRegistry registry = new ModelRegistry(props);

    assertThatThrownBy(registry::validate)
        .isInstanceOf(IllegalStateException.class)
        // planning + workhorse + alias violations are all surfaced in one exception.
        .hasMessageContaining("bad-planning")
        .hasMessageContaining("bad-workhorse")
        .hasMessageContaining("unknown-target");
  }

  @Test
  void validate_throws_whenFallbackChainReferencesUnknownModel() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("known-model", model("openai")));
    ModelMatrixProperties.FallbackEntry fb = new ModelMatrixProperties.FallbackEntry();
    fb.setProvider("openai");
    fb.setModel("missing-fallback");
    props.setFallbackChains(Map.of("CAPABLE", List.of(fb)));
    props.setFreeTier(freeTier("openai", "known-model"));

    ModelRegistry registry = new ModelRegistry(props);

    assertThatThrownBy(registry::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing-fallback");
  }

  @Test
  void validate_passes_whenMatrixConsistent() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("known-model", model("openai")));
    props.setProviders(
        Map.of("openai", providerModels("known-model", "known-model", "known-model")));
    props.setFreeTier(freeTier("openai", "known-model"));

    ModelRegistry registry = new ModelRegistry(props);

    assertThatCode(registry::validate).doesNotThrowAnyException();
    assertThat(registry.isKnownModel("known-model")).isTrue();
  }
}
