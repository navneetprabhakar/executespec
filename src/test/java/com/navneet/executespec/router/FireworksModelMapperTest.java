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
    assertThat(FireworksModelMapper.toSlug("kimi-k2p6-fw"))
        .isEqualTo("accounts/fireworks/models/kimi-k2p6");
    assertThat(FireworksModelMapper.toSlug("glm-5.1-fw"))
        .isEqualTo("accounts/fireworks/models/glm-5p1");
    // 2026-07-24 — exact account-visible Fireworks serverless coding subset.
    assertThat(FireworksModelMapper.toSlug("glm-5.2-fw"))
        .isEqualTo("accounts/fireworks/models/glm-5p2");
    assertThat(FireworksModelMapper.toSlug("glm-5.2-fast-fw"))
        .isEqualTo("accounts/fireworks/routers/glm-5p2-fast");
    assertThat(FireworksModelMapper.allowList()).hasSize(5);
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
    assertThatThrownBy(() -> FireworksModelMapper.toSlug("deepseek-v4-flash-fw"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FireworksModelMapper.toSlug("minimax-m3-fw"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
