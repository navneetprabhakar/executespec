package com.navneet.executespec.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PipelineE2ERouteTest {
  @Test
  void costCapDefaultsToTenCentsAndAllowsBoundedReviewerHeadroom() {
    assertThat(PipelineE2ERoute.costCap(Map.of())).isEqualTo(0.10);
    assertThat(PipelineE2ERoute.costCap(Map.of("E2E_COST_CAP_USD", "0.50"))).isEqualTo(0.50);
  }

  @Test
  void rejectsUnboundedOrBelowApiMinimumCostCaps() {
    for (String cap :
        java.util.List.of("NaN", "Infinity", "0", "-1", "0.025", "0.099999", "0.51", "invalid")) {
      assertThatThrownBy(() -> PipelineE2ERoute.costCap(Map.of("E2E_COST_CAP_USD", cap)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void defaultsToExistingDeepSeekRoute() {
    assertThat(PipelineE2ERoute.from(Map.of()))
        .isEqualTo(new PipelineE2ERoute("DEEPSEEK", "deepseek-flash-native"));
  }

  @Test
  void selectsEachProviderWithoutCrossProviderFallback() {
    Map.of(
            "anthropic",
            "claude-haiku-5-5",
            "openai",
            "gpt-6-luna",
            "gemini",
            "gemini-3.8-flash",
            "openrouter",
            "deepseek-v4.1-flash-1017",
            "fireworks",
            "deepseek-v4p1-flash-1017-fw")
        .forEach(
            (provider, model) -> {
              assertThat(PipelineE2ERoute.from(Map.of("E2E_PROVIDER", provider)))
                  .isEqualTo(
                      new PipelineE2ERoute(provider.toUpperCase(java.util.Locale.ROOT), model));
            });
  }

  @Test
  void acceptsCurrentSecondaryModelButRejectsCrossProviderSubstitution() {
    assertThat(PipelineE2ERoute.from(Map.of("E2E_PROVIDER", "openai", "E2E_MODEL", "gpt-6.1-sol")))
        .isEqualTo(new PipelineE2ERoute("OPENAI", "gpt-6.1-sol"));
  }

  @Test
  void rejectsUnboundedModelAndUnknownProviderBeforeStartup() {
    assertThatThrownBy(() -> PipelineE2ERoute.from(Map.of("E2E_MODEL", "claude-opus-5")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PipelineE2ERoute.from(Map.of("E2E_PROVIDER", "sarvam")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PipelineE2ERoute.from(
                    Map.of("E2E_PROVIDER", "openai", "E2E_MODEL", "deepseek-flash-native")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
