package com.navneet.executespec.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ContinuationConfigDefaultsTest {

  @Test
  void automaticContinuationDoesNotRequireBenchmarkPriorByDefault() throws Exception {
    Map<String, Object> root = loadApplicationYaml();

    @SuppressWarnings("unchecked")
    Map<String, Object> execution = (Map<String, Object>) root.get("execution");
    assertNotNull(execution, "execution section missing from application.yml");

    @SuppressWarnings("unchecked")
    Map<String, Object> continuation = (Map<String, Object>) execution.get("continuation");
    assertNotNull(continuation, "execution.continuation section missing from application.yml");

    assertEquals(
        Boolean.FALSE,
        continuation.get("require-benchmark-prior-for-automatic-continuation"),
        "Automatic continuation must work before every model has benchmark priors; users can opt"
            + " into benchmark-required upgrades per run or server config.");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadApplicationYaml() throws Exception {
    try (InputStream in =
        ContinuationConfigDefaultsTest.class.getResourceAsStream("/application.yml")) {
      assertNotNull(in, "application.yml must be on the classpath");
      Yaml yaml = new Yaml();
      Map<String, Object> merged = new LinkedHashMap<>();
      for (Object doc : yaml.loadAll(in)) {
        if (doc instanceof Map) {
          merged.putAll((Map<String, Object>) doc);
        }
      }
      return merged;
    }
  }
}
