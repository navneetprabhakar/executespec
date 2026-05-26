package com.navneet.executespec.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.router.LlmProvider;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Regression guard for the v2.6.0 planner crash: {@code application.yml} {@code
 * model-matrix.failover-order} listed {@code "google"} in multiple places even though the {@link
 * LlmProvider} enum was renamed {@code GOOGLE → GEMINI} in v2.5.13. When {@code
 * ModelResolver.selectAdaptiveProvider} iterated the list and called {@code
 * LlmProvider.valueOf(name.toUpperCase())}, it threw {@code IllegalArgumentException: No enum
 * constant LlmProvider.GOOGLE} every single time the planner ran — the run failed immediately with
 * zero tokens spent, silently refunded, and the user saw nothing but a FAILED badge.
 *
 * <p>This test asserts the whole invariant: every provider name that appears in the failover-order
 * map (as a top-level key OR inside a list value) MUST resolve to a valid {@link LlmProvider} enum
 * constant via the same call pattern {@code ModelResolver} uses.
 *
 * <p>Breaks immediately in CI if anyone edits {@code application.yml} and introduces a provider
 * name that diverges from the enum (typo, rename without updating yaml, etc.) — stops the
 * regression before it ships to dev or prod.
 */
class FailoverOrderEnumAlignmentTest {

  @Test
  void allFailoverEntriesMatchLlmProviderEnum() throws Exception {
    Map<String, Object> root = loadApplicationYaml();

    @SuppressWarnings("unchecked")
    Map<String, Object> modelMatrix = (Map<String, Object>) root.get("model-matrix");
    assertNotNull(modelMatrix, "model-matrix section missing from application.yml");

    @SuppressWarnings("unchecked")
    Map<String, List<String>> failoverOrder =
        (Map<String, List<String>>) modelMatrix.get("failover-order");
    assertNotNull(failoverOrder, "model-matrix.failover-order missing from application.yml");
    assertTrue(!failoverOrder.isEmpty(), "failover-order must not be empty");

    for (Map.Entry<String, List<String>> entry : failoverOrder.entrySet()) {
      String primary = entry.getKey();
      assertValidProvider(primary, "failover-order key");

      List<String> fallbacks = entry.getValue();
      assertNotNull(fallbacks, "failover-order[" + primary + "] list is null");
      for (String fallback : fallbacks) {
        assertValidProvider(fallback, "failover-order[" + primary + "] entry");
      }
    }
  }

  @Test
  void allModelMatrixProviderKeysMatchLlmProviderEnum() throws Exception {
    Map<String, Object> root = loadApplicationYaml();

    @SuppressWarnings("unchecked")
    Map<String, Object> modelMatrix = (Map<String, Object>) root.get("model-matrix");
    assertNotNull(modelMatrix, "model-matrix section missing from application.yml");

    @SuppressWarnings("unchecked")
    Map<String, Object> providers = (Map<String, Object>) modelMatrix.get("providers");
    assertNotNull(providers, "model-matrix.providers missing from application.yml");

    for (String provider : providers.keySet()) {
      assertValidProvider(provider, "model-matrix.providers key");
    }
  }

  // (Removed RL-2026-05-041) allProviderRunPoolKeysMatchLlmProviderEnum — it guarded the
  // now-deleted
  // ProviderRunPoolProperties / agent.provider-pools config (audit risk #3). The failover-order
  // alignment test above is the live invariant; provider-pool key alignment no longer exists.

  // ── helpers ──────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadApplicationYaml() throws Exception {
    // application.yml is a multi-document file (separated by `---`).
    // Merge every top-level key from every doc into a single flat map so
    // all three test cases can look up their config sections.
    try (InputStream in =
        FailoverOrderEnumAlignmentTest.class.getResourceAsStream("/application.yml")) {
      assertNotNull(in, "application.yml must be on the classpath");
      Yaml yaml = new Yaml();
      Map<String, Object> merged = new java.util.LinkedHashMap<>();
      for (Object doc : yaml.loadAll(in)) {
        if (doc instanceof Map) {
          merged.putAll((Map<String, Object>) doc);
        }
      }
      return merged;
    }
  }

  private static void assertValidProvider(String name, String context) {
    try {
      LlmProvider.valueOf(name.toUpperCase());
    } catch (IllegalArgumentException e) {
      fail(
          context
              + " '"
              + name
              + "' does not resolve to any LlmProvider enum "
              + "constant ("
              + validProviderNames()
              + "). "
              + "ModelResolver.selectAdaptiveProvider would throw at runtime, "
              + "failing every planner run.");
    }
  }

  private static String validProviderNames() {
    StringBuilder sb = new StringBuilder();
    for (LlmProvider p : LlmProvider.values()) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(p.name());
    }
    return sb.toString();
  }

  @Test
  void testEnumItselfContainsExpectedProviders() {
    // Cheap sanity check: if someone ADDS a provider to the enum, these
    // tests should keep passing — but if someone REMOVES one, the
    // failover-order test above will catch it via the yaml check.
    assertEquals(
        4,
        LlmProvider.values().length,
        "Unexpected LlmProvider enum size — if you added a new provider, update application.yml's"
            + " failover-order + model-matrix.providers to include it."
            + " If you removed one, update the same two yaml sections to drop references."
            + " (OPENROUTER added 2026-05-21 — model-matrix.providers.openrouter is set; it is an"
            + " enterprise gateway, intentionally NOT in failover-order.)"
            + " (agent.provider-pools was removed 2026-05-26, RL-2026-05-041 — no longer a section"
            + " to update.)");
  }
}
