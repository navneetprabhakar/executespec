package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class GeminiModelCatalogConfigurationTest {

  @Test
  void latestGeminiCatalogAndSafeDefaultsAreConfigured() throws Exception {
    Map<String, Object> matrix = map(loadYaml().get("model-matrix"));
    Map<String, Object> models = normalizedKeys(map(matrix.get("models")));
    Map<String, Object> gemini = map(map(matrix.get("providers")).get("gemini"));
    Map<String, Object> aliases = map(matrix.get("legacy-aliases"));

    assertThat(gemini)
        .containsEntry("planning", "gemini-3.5-flash")
        .containsEntry("workhorse", "gemini-3.5-flash")
        .containsEntry("lightweight", "gemini-3.5-flash-lite");
    assertThat(models.keySet())
        .contains(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.1-pro-preview")
        .doesNotContain("gemini-3-flash-preview", "gemini-3-pro-preview");
    assertThat(aliases)
        .containsEntry("gemini-3-flash-preview", "gemini-3.6-flash")
        .containsEntry("gemini-3-pro-preview", "gemini-3.1-pro-preview");

    Map<String, Object> flash36 = map(models.get("gemini-3.6-flash"));
    assertThat(flash36)
        .containsEntry("context-limit", 1_048_576)
        .containsEntry("max-output-tokens", 65_536)
        .containsEntry("managed-ai-enabled", true);
    assertThat(map(flash36.get("pricing")))
        .containsEntry("input-per-1m", 1.5)
        .containsEntry("cached-input-per-1m", 0.15)
        .containsEntry("output-per-1m", 7.5);

    Map<String, Object> pro31 = map(models.get("gemini-3.1-pro-preview"));
    assertThat(pro31).containsEntry("managed-ai-enabled", true);
    assertThat(map(pro31.get("pricing")))
        .containsEntry("input-per-1m", 4.0)
        .containsEntry("cached-input-per-1m", 0.4)
        .containsEntry("output-per-1m", 18.0);
  }

  private static Map<String, Object> loadYaml() throws Exception {
    try (InputStream in =
        GeminiModelCatalogConfigurationTest.class.getResourceAsStream("/application.yml")) {
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
