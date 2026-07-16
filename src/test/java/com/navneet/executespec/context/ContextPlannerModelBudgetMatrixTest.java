package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Deterministic, provider-free evidence that every configured model has a usable context budget.
 *
 * <p>This is deliberately a budget-contract test, not a model-quality test: it proves that the YAML
 * profile is loaded, that output/tool/verification/handoff reservations are applied per model, and
 * that the compaction boundary is one token wide. It makes no provider calls and does not infer
 * quality from context size.
 */
class ContextPlannerModelBudgetMatrixTest {

  @Test
  void everyConfiguredModelHasDeterministicContinueAndCompactBoundaries() throws Exception {
    ModelMatrixProperties properties = propertiesFromApplicationYaml();
    ModelRegistry registry = new ModelRegistry(properties);
    ContextPlanner planner = new ContextPlanner(registry);

    assertThat(registry.allModels()).isNotEmpty();
    for (Map.Entry<String, ModelMatrixProperties.ModelConfig> entry :
        registry.allModels().entrySet()) {
      String model = entry.getKey();
      ModelMatrixProperties.ModelConfig config = entry.getValue();
      int window = registry.contextLimitFor(model);
      int output = registry.maxOutputForModel(model);

      assertThat(config.getProvider()).as("provider for %s", model).isNotBlank();
      assertThat(window).as("context window for %s", model).isPositive();
      assertThat(output).as("max output for %s", model).isPositive();
      assertThat(output)
          .as("output reservation must fit model window for %s", model)
          .isLessThan(window);

      long reserves = fixedReserves(window, output);
      long boundaryInput = window - output - reserves;
      assertThat(boundaryInput)
          .as("computed boundary must be non-negative for %s", model)
          .isGreaterThanOrEqualTo(0);

      var continueDecision =
          planner.planHeadroom(
              config.getProvider(), model, boundaryInput, output, List.of(), List.of());
      assertThat(continueDecision.action())
          .as("exact boundary for %s", model)
          .isEqualTo(ContextPlanner.HeadroomAction.CONTINUE);
      assertThat(continueDecision.availableHeadroomTokens())
          .as("available headroom at exact boundary for %s", model)
          .isZero();

      var compactDecision =
          planner.planHeadroom(
              config.getProvider(), model, boundaryInput + 1, output, List.of(), List.of());
      assertThat(compactDecision.action())
          .as("one token past boundary for %s", model)
          .isEqualTo(ContextPlanner.HeadroomAction.COMPACT);
      assertThat(compactDecision.availableHeadroomTokens())
          .as("available headroom one token past boundary for %s", model)
          .isEqualTo(-1);
    }
  }

  @Test
  void providerTierDefaultsResolveToRegisteredModelProfiles() throws Exception {
    Map<String, Object> root = loadYaml();
    @SuppressWarnings("unchecked")
    Map<String, Object> matrix = (Map<String, Object>) root.get("model-matrix");
    @SuppressWarnings("unchecked")
    Map<String, Object> providers = (Map<String, Object>) matrix.get("providers");
    ModelRegistry registry = new ModelRegistry(propertiesFromApplicationYaml());

    for (Map.Entry<String, Object> provider : providers.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> tiers = (Map<String, Object>) provider.getValue();
      for (String tier : List.of("planning", "workhorse", "lightweight")) {
        String model = String.valueOf(tiers.get(tier));
        assertThat(registry.isKnownModel(model))
            .as("%s.%s model %s must have a context profile", provider.getKey(), tier, model)
            .isTrue();
        assertThat(registry.contextLimitFor(model)).isPositive();
        assertThat(registry.maxOutputForModel(model)).isPositive();
      }
    }
  }

  private static long fixedReserves(int window, int output) {
    int safety =
        Math.max(
            ContextPlanner.MIN_SAFETY_TOKENS,
            (int) Math.ceil(window * ContextPlanner.SAFETY_RATIO));
    int tool =
        Math.max(ContextPlanner.MIN_TOOL_RESULT_RESERVE_TOKENS, (int) Math.ceil(output * 0.25));
    int verification =
        Math.max(ContextPlanner.MIN_VERIFICATION_RESERVE_TOKENS, (int) Math.ceil(output * 0.25));
    int handoff =
        Math.max(ContextPlanner.MIN_HANDOFF_RESERVE_TOKENS, (int) Math.ceil(output * 0.25));
    return (long) safety + tool + verification + handoff;
  }

  private static ModelMatrixProperties propertiesFromApplicationYaml() throws Exception {
    Map<String, Object> root = loadYaml();
    @SuppressWarnings("unchecked")
    Map<String, Object> matrix = (Map<String, Object>) root.get("model-matrix");
    @SuppressWarnings("unchecked")
    Map<String, Object> rawModels = (Map<String, Object>) matrix.get("models");
    ModelMatrixProperties properties = new ModelMatrixProperties();
    Map<String, ModelMatrixProperties.ModelConfig> models = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : rawModels.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = (Map<String, Object>) entry.getValue();
      ModelMatrixProperties.ModelConfig config = new ModelMatrixProperties.ModelConfig();
      config.setProvider(String.valueOf(raw.get("provider")));
      config.setContextLimit(number(raw.get("context-limit"), 200_000));
      config.setMaxOutputTokens(number(raw.get("max-output-tokens"), 16_000));
      models.put(normalizeMapKey(entry.getKey()), config);
    }
    properties.setModels(models);
    return properties;
  }

  private static Map<String, Object> loadYaml() throws Exception {
    try (InputStream in =
        ContextPlannerModelBudgetMatrixTest.class.getResourceAsStream("/application.yml")) {
      assertNotNull(in, "application.yml must be on the classpath");
      Map<String, Object> merged = new LinkedHashMap<>();
      for (Object document : new Yaml().loadAll(in)) {
        if (document instanceof Map<?, ?> map) {
          for (Map.Entry<?, ?> entry : map.entrySet())
            merged.put(String.valueOf(entry.getKey()), entry.getValue());
        }
      }
      return merged;
    }
  }

  private static int number(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  /** Spring's bracket-map-key notation protects dotted model ids; SnakeYAML leaves the brackets. */
  private static String normalizeMapKey(String key) {
    return key != null && key.startsWith("[") && key.endsWith("]")
        ? key.substring(1, key.length() - 1)
        : key;
  }
}
