package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 2026-05-06 — pins the {@link ModelRegistry#contextLimitForOrPrefixFallback} contract that
 * replaced the deleted {@code ModelContextWindowMap}.
 *
 * <p>The drift the map had introduced: gpt-5.4 = 256K in the map vs 200K in YAML. After the
 * consolidation, YAML wins. The prefix-fallback only fires for forward-compat unknowns.
 */
class ModelRegistryContextLimitTest {

  private ModelMatrixProperties props;
  private ModelRegistry registry;

  @BeforeEach
  void setUp() {
    props = new ModelMatrixProperties();
    Map<String, ModelMatrixProperties.ModelConfig> models = new HashMap<>();

    models.put("claude-sonnet-4-6", model("anthropic", 200_000));
    models.put("claude-haiku-4-5-20251001", model("anthropic", 200_000));
    models.put("gemini-3-flash-preview", model("gemini", 1_048_576));
    models.put("gemini-3-pro-preview", model("gemini", 1_048_576));
    // The audit's headline drift: YAML says gpt-5.4 = 200K, the deleted map said 256K.
    // YAML wins after this refactor.
    models.put("gpt-5.4", model("openai", 200_000));
    models.put("gpt-5.4-mini", model("openai", 200_000));

    props.setModels(models);
    registry = new ModelRegistry(props);
  }

  @Test
  void knownModel_returnsYamlValue() {
    // The whole point — gpt-5.4 = 200K, NOT the deleted map's 256K.
    assertThat(registry.contextLimitForOrPrefixFallback("gpt-5.4")).isEqualTo(200_000);
    assertThat(registry.contextLimitForOrPrefixFallback("gpt-5.4-mini")).isEqualTo(200_000);
    assertThat(registry.contextLimitForOrPrefixFallback("claude-sonnet-4-6")).isEqualTo(200_000);
    assertThat(registry.contextLimitForOrPrefixFallback("gemini-3-flash-preview"))
        .isEqualTo(1_048_576);
  }

  @Test
  void unknownClaudeModel_fallsBackTo200K() {
    assertThat(registry.contextLimitForOrPrefixFallback("claude-sonnet-4-7")).isEqualTo(200_000);
  }

  @Test
  void unknownClaudeOpus1M_fallsBackTo1M() {
    // The map had a special-case for the 1M-context Opus variant. Preserved in the registry
    // fallback so the chat-header chip doesn't lose this when a new opus-*-1m ships.
    assertThat(registry.contextLimitForOrPrefixFallback("claude-opus-4-7-1m")).isEqualTo(1_000_000);
  }

  @Test
  void unknownGeminiModel_fallsBackTo1M() {
    assertThat(registry.contextLimitForOrPrefixFallback("gemini-future-model")).isEqualTo(1_048_576);
  }

  @Test
  void unknownGptModel_fallsBackTo256K() {
    // For unknown gpt-* models we lean on the published GPT-5.x family number (256K). This is
    // the optimistic forward-compat default.
    assertThat(registry.contextLimitForOrPrefixFallback("gpt-6")).isEqualTo(256_000);
  }

  @Test
  void completelyUnknownModel_fallsBackToDefault() {
    assertThat(registry.contextLimitForOrPrefixFallback("anthropic-secret")).isEqualTo(200_000);
    assertThat(registry.contextLimitForOrPrefixFallback("totally-made-up")).isEqualTo(200_000);
  }

  @Test
  void nullOrBlank_returnsDefault() {
    assertThat(registry.contextLimitForOrPrefixFallback(null)).isEqualTo(200_000);
    assertThat(registry.contextLimitForOrPrefixFallback("")).isEqualTo(200_000);
    assertThat(registry.contextLimitForOrPrefixFallback("   ")).isEqualTo(200_000);
  }

  private static ModelMatrixProperties.ModelConfig model(String provider, int contextLimit) {
    ModelMatrixProperties.ModelConfig c = new ModelMatrixProperties.ModelConfig();
    c.setProvider(provider);
    c.setContextLimit(contextLimit);
    return c;
  }
}
