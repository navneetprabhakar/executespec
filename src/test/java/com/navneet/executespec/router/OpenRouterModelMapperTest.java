package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the verified matrix-id → OpenRouter-slug allow-list (checked against the live OpenRouter
 * catalogue 2026-05-21). If a matrix model id changes, this test fails until the slug is
 * re-verified.
 */
class OpenRouterModelMapperTest {

  @Test
  void maps_every_actively_tier_routed_model_to_its_verified_slug() {
    assertThat(OpenRouterModelMapper.toSlug("claude-sonnet-4-6"))
        .isEqualTo("anthropic/claude-sonnet-4.6");
    assertThat(OpenRouterModelMapper.toSlug("claude-haiku-4-5-20251001"))
        .isEqualTo("anthropic/claude-haiku-4.5");
    assertThat(OpenRouterModelMapper.toSlug("claude-opus-4-6"))
        .isEqualTo("anthropic/claude-opus-4.6");
    assertThat(OpenRouterModelMapper.toSlug("gpt-5.4")).isEqualTo("openai/gpt-5.4");
    assertThat(OpenRouterModelMapper.toSlug("gpt-5.4-mini")).isEqualTo("openai/gpt-5.4-mini");
    assertThat(OpenRouterModelMapper.toSlug("gemini-3.5-flash"))
        .isEqualTo("google/gemini-3.5-flash");
    assertThat(OpenRouterModelMapper.toSlug("gemini-3-flash-preview"))
        .isEqualTo("google/gemini-3-flash-preview");
  }

  @Test
  void unmapped_model_throws_so_we_never_send_an_unverified_slug() {
    // gemini-3-pro-preview has no exact OpenRouter slug + is not tier-routed → must fail loudly.
    assertThatThrownBy(() -> OpenRouterModelMapper.toSlug("gemini-3-pro-preview"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No verified OpenRouter slug");
    assertThatThrownBy(() -> OpenRouterModelMapper.toSlug("made-up-model"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isSupported_is_consistent_with_toSlug() {
    assertThat(OpenRouterModelMapper.isSupported("gpt-5.4")).isTrue();
    assertThat(OpenRouterModelMapper.isSupported("gemini-3-pro-preview")).isFalse();
    assertThat(OpenRouterModelMapper.isSupported(null)).isFalse();
  }
}
