package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the verified matrix-id → OpenRouter-slug allow-list (checked against the live OpenRouter
 * catalogue 2026-07-24). If a matrix model id changes, this test fails until the slug is
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
    assertThat(OpenRouterModelMapper.toSlug("claude-fable-5"))
        .isEqualTo("anthropic/claude-fable-5");
    assertThat(OpenRouterModelMapper.toSlug("gpt-5.6-sol")).isEqualTo("openai/gpt-5.6-sol");
    assertThat(OpenRouterModelMapper.toSlug("gpt-5.6-terra")).isEqualTo("openai/gpt-5.6-terra");
    assertThat(OpenRouterModelMapper.toSlug("gpt-5.6-luna")).isEqualTo("openai/gpt-5.6-luna");
    assertThat(OpenRouterModelMapper.toSlug("gemini-3.6-flash"))
        .isEqualTo("google/gemini-3.6-flash");
  }

  @Test
  void maps_deepseek_and_kimi_to_verified_slugs() {
    // RL-2026-05-048 — OpenRouter-only models (ENTERPRISE BYOK). Slugs verified 2026-05-26.
    assertThat(OpenRouterModelMapper.toSlug("deepseek-v4-pro"))
        .isEqualTo("deepseek/deepseek-v4-pro");
    assertThat(OpenRouterModelMapper.toSlug("deepseek-v4-flash"))
        .isEqualTo("deepseek/deepseek-v4-flash");
    assertThat(OpenRouterModelMapper.toSlug("kimi-k2.6")).isEqualTo("moonshotai/kimi-k2.6");
    assertThat(OpenRouterModelMapper.isSupported("deepseek-v4-pro")).isTrue();
    assertThat(OpenRouterModelMapper.isSupported("kimi-k2.6")).isTrue();
  }

  @Test
  void maps_latest_openrouter_only_agentic_models() {
    assertThat(OpenRouterModelMapper.toSlug("kimi-k3")).isEqualTo("moonshotai/kimi-k3");
    assertThat(OpenRouterModelMapper.toSlug("qwen3.7-plus")).isEqualTo("qwen/qwen3.7-plus");
    assertThat(OpenRouterModelMapper.toSlug("qwen3.7-max")).isEqualTo("qwen/qwen3.7-max");
    assertThat(OpenRouterModelMapper.toSlug("grok-4.5")).isEqualTo("x-ai/grok-4.5");
  }

  @Test
  void unmapped_model_throws_so_we_never_send_an_unverified_slug() {
    // The retired raw id is canonicalized before dispatch and must not be sent directly.
    assertThatThrownBy(() -> OpenRouterModelMapper.toSlug("gemini-3-pro-preview"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No verified OpenRouter slug");
    assertThatThrownBy(() -> OpenRouterModelMapper.toSlug("made-up-model"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isSupported_is_consistent_with_toSlug() {
    assertThat(OpenRouterModelMapper.isSupported("gpt-5.4")).isTrue();
    assertThat(OpenRouterModelMapper.isSupported("gemini-3-flash-preview")).isFalse();
    assertThat(OpenRouterModelMapper.isSupported("gemini-3-pro-preview")).isFalse();
    assertThat(OpenRouterModelMapper.isSupported(null)).isFalse();
  }
}
