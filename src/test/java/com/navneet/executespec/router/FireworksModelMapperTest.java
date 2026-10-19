package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pins the Fireworks matrix-id to model-slug allow-list. */
class FireworksModelMapperTest {

  @Test
  void maps_fireworks_models_to_current_slugs() {
    assertThat(FireworksModelMapper.toSlug("deepseek-v4p1-flash-fw"))
        .isEqualTo("accounts/fireworks/models/deepseek-v4p1-flash");
    assertThat(FireworksModelMapper.toSlug("deepseek-v4p1-flash-1017-fw"))
        .isEqualTo("accounts/fireworks/models/deepseek-v4p1-flash-1017");
    assertThat(FireworksModelMapper.toSlug("glm-5p3-flash-fw"))
        .isEqualTo("accounts/fireworks/models/glm-5p3-flash");
    assertThat(FireworksModelMapper.allowList()).hasSize(3);
  }

  @Test
  void retiredPinsFailBeforeTransportInsteadOfSilentlySelectingAnotherModel() {
    for (String retired :
        java.util.List.of(
            "deepseek-v4-pro-fw",
            "kimi-k2p6-fw",
            "glm-5.2-fw",
            "glm-5.2-fast-fw",
            "deepseek-v4-flash-0731-fw")) {
      assertThatThrownBy(() -> FireworksModelMapper.toSlug(retired))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(FireworksModelMapper.isSupported(retired)).isFalse();
    }
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
    // Removed 2026-08-06: accounts/fireworks/models/glm-5p1 is absent from the live
    // Fireworks catalogue, so dispatching it 404s. A pinned run cannot fail over,
    // which makes a dead slug an unrecoverable failure — keep it unmapped.
    assertThatThrownBy(() -> FireworksModelMapper.toSlug("glm-5.1-fw"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
