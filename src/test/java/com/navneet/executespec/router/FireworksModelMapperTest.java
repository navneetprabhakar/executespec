package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pins the Fireworks matrix-id to model-slug allow-list. */
class FireworksModelMapperTest {

  @Test
  void maps_fireworks_models_to_current_slugs() {
    assertThat(FireworksModelMapper.toSlug("deepseek-v4-pro-fw"))
        .isEqualTo("accounts/fireworks/models/deepseek-v4-pro");
    assertThat(FireworksModelMapper.toSlug("deepseek-v4-flash-fw"))
        .isEqualTo("accounts/fireworks/models/deepseek-v4-flash");
    assertThat(FireworksModelMapper.toSlug("kimi-k2p6-fw"))
        .isEqualTo("accounts/fireworks/models/kimi-k2p6");
    assertThat(FireworksModelMapper.toSlug("glm-5.1-fw"))
        .isEqualTo("accounts/fireworks/models/glm-5p1");
    // 2026-06-18 — Fireworks-available subset of the OpenRouter coding models (live-probed).
    assertThat(FireworksModelMapper.toSlug("glm-5.2-fw"))
        .isEqualTo("accounts/fireworks/models/glm-5p2");
    assertThat(FireworksModelMapper.toSlug("minimax-m3-fw"))
        .isEqualTo("accounts/fireworks/models/minimax-m3");
    assertThat(FireworksModelMapper.toSlug("minimax-m2.7-fw"))
        .isEqualTo("accounts/fireworks/models/minimax-m2p7");
    assertThat(FireworksModelMapper.toSlug("kimi-k2.7-code-fw"))
        .isEqualTo("accounts/fireworks/models/kimi-k2p7-code");
  }

  @Test
  void full_fireworks_slug_passes_through() {
    assertThat(FireworksModelMapper.toSlug("accounts/fireworks/models/deepseek-v4-flash"))
        .isEqualTo("accounts/fireworks/models/deepseek-v4-flash");
  }

  @Test
  void unmapped_model_throws() {
    assertThatThrownBy(() -> FireworksModelMapper.toSlug("deepseek-v4-flash"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No verified Fireworks slug");
    assertThat(FireworksModelMapper.isSupported(null)).isFalse();
  }
}
