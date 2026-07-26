package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import com.navneet.executespec.router.LlmProvider;

import org.junit.jupiter.api.Test;

/**
 * RR-568 — the classifier cache key must fingerprint project type, selected provider and
 * project-memory so a change in any of those does not silently reuse a verdict produced under
 * different evidence/route.
 */
class SmartClassifierCacheKeyTest {

  private static String key(
      String spec, List<String> repos, String projectId, String type, LlmProvider p, String mem)
      throws Exception {
    Method m =
        SmartClassifierAgent.class.getDeclaredMethod(
            "cacheKey",
            String.class,
            List.class,
            String.class,
            String.class,
            LlmProvider.class,
            String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, spec, repos, projectId, type, p, mem);
  }

  @Test
  void providerChangesTheKey() throws Exception {
    String a = key("spec", List.of("r"), "p", "web", LlmProvider.ANTHROPIC, "mem");
    String b = key("spec", List.of("r"), "p", "web", LlmProvider.OPENAI, "mem");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void projectTypeAndMemoryChangeTheKey() throws Exception {
    String base = key("spec", List.of("r"), "p", "web", LlmProvider.ANTHROPIC, "mem");
    String type = key("spec", List.of("r"), "p", "cli", LlmProvider.ANTHROPIC, "mem");
    String mem = key("spec", List.of("r"), "p", "web", LlmProvider.ANTHROPIC, "mem2");
    assertThat(type).isNotEqualTo(base);
    assertThat(mem).isNotEqualTo(base);
  }

  @Test
  void identicalInputsShareOneKey() throws Exception {
    assertThat(key("spec", List.of("r"), "p", "web", LlmProvider.ANTHROPIC, "mem"))
        .isEqualTo(key("spec", List.of("r"), "p", "web", LlmProvider.ANTHROPIC, "mem"));
  }

  @Test
  void keyIsNamespacedByClassifierPolicyVersion() throws Exception {
    assertThat(key("spec", List.of("r"), "p", "web", LlmProvider.ANTHROPIC, "mem"))
        .startsWith("planner:classify:v3:");
  }
}
