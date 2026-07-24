package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ProviderModelCatalogConfigurationTest {

  @Test
  void currentAccountVisibleModelsAreRegisteredAndFalseClaimsAreRemoved() throws Exception {
    Map<String, Object> matrix = map(loadYaml().get("model-matrix"));
    Map<String, Object> models = normalizedKeys(map(matrix.get("models")));
    Map<String, Object> providers = map(matrix.get("providers"));
    Map<String, Object> aliases = map(matrix.get("legacy-aliases"));

    assertThat(models.keySet())
        .contains(
            "claude-fable-5",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "deepseek-v4-pro-native",
            "deepseek-v4-flash-native",
            "kimi-k3",
            "qwen3.7-plus",
            "qwen3.7-max",
            "grok-4.5")
        .doesNotContain(
            "gpt-5.5-mini",
            "deepseek-v4-flash-fw",
            "minimax-m3-fw",
            "minimax-m2.7-fw",
            "kimi-k2.7-code-fw");

    assertThat(map(providers.get("fireworks")))
        .containsEntry("planning", "deepseek-v4-pro-fw")
        .containsEntry("workhorse", "deepseek-v4-pro-fw")
        .containsEntry("lightweight", "kimi-k2p6-fw");
    assertThat(aliases)
        .containsEntry("gpt-5.5-mini", "gpt-5.6-luna")
        .containsEntry("deepseek-v4-flash-fw", "kimi-k2p6-fw")
        .containsEntry("minimax-m3-fw", "glm-5.2-fw")
        .containsEntry("kimi-k2.7-code-fw", "kimi-k2p6-fw");

    for (String conservativelyPricedModel :
        new String[] {"gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "qwen3.7-plus", "grok-4.5"}) {
      assertThat(map(models.get(conservativelyPricedModel)))
          .as("managed AI must remain metered for %s", conservativelyPricedModel)
          .containsEntry("managed-ai-enabled", true);
    }
  }

  private static Map<String, Object> loadYaml() throws Exception {
    try (InputStream in =
        ProviderModelCatalogConfigurationTest.class.getResourceAsStream("/application.yml")) {
      assertNotNull(in, "application.yml must be on the classpath");
      Map<String, Object> merged = new LinkedHashMap<>();
      for (Object document : new Yaml().loadAll(in)) {
        if (document instanceof Map<?, ?> values) {
          values.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
      }
      return merged;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> normalizedKeys(Map<String, Object> source) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    source.forEach((key, value) -> normalized.put(normalizeKey(key), value));
    return normalized;
  }

  private static String normalizeKey(String key) {
    return key != null && key.startsWith("[") && key.endsWith("]")
        ? key.substring(1, key.length() - 1)
        : key;
  }
}
