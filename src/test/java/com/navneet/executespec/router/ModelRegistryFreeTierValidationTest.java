package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * RR-115 (2026-07-21) — Free-tier routing is billing-critical and must fail closed, never fall back
 * to a stale paid route.
 */
class ModelRegistryFreeTierValidationTest {

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

  @Test
  void validate_throws_whenFreeTierMissing() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("deepseek-v4-flash-native", model("deepseek")));
    ModelRegistry registry = new ModelRegistry(props);

    assertThatThrownBy(registry::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("free-tier");
  }

  @Test
  void validate_throws_whenFreeTierModelUnknown() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("deepseek-v4-flash-native", model("deepseek")));
    // A configured but unresolvable free-tier model must fail startup, not silently take a route.
    props.setFreeTier(freeTier("openai", "gpt-5.4-mini"));
    ModelRegistry registry = new ModelRegistry(props);

    assertThatThrownBy(registry::validate).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validate_passes_andGettersReturnConfigured_whenFreeTierPresent() {
    ModelMatrixProperties props = new ModelMatrixProperties();
    props.setModels(Map.of("deepseek-v4-flash-native", model("deepseek")));
    props.setFreeTier(freeTier("deepseek", "deepseek-v4-flash-native"));
    ModelRegistry registry = new ModelRegistry(props);

    registry.validate(); // does not throw
    assertThat(registry.freeTierProvider()).isEqualTo("deepseek");
    assertThat(registry.freeTierModel()).isEqualTo("deepseek-v4-flash-native");
  }

  @Test
  void freeTierGetters_failClosed_notToPaidRoute_whenMissing() {
    ModelRegistry registry = new ModelRegistry(new ModelMatrixProperties());
    // No behavioural fallback to gpt-5.4-mini / openai (RR-115).
    assertThatThrownBy(registry::freeTierModel).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(registry::freeTierProvider).isInstanceOf(IllegalStateException.class);
  }
}
