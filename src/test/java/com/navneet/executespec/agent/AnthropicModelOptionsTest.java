package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnthropicModelOptionsTest {

  @Test
  void omitsTemperatureForKnownDeprecatedModelFamilies() {
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-sonnet-5")).isTrue();
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-sonnet-5-20260702")).isTrue();
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-opus-4-8")).isTrue();
    assertThat(AnthropicModelOptions.shouldOmitTemperature("claude-sonnet-4-6")).isFalse();
  }

  @Test
  void recognizesAnthropicBacktickWrappedTemperatureDeprecation() {
    RuntimeException ex =
        new RuntimeException(
            "400 - {\"error\":{\"message\":\"`temperature` is deprecated for this model.\"}}");

    assertThat(AnthropicModelOptions.isTemperatureDeprecatedError(ex)).isTrue();
  }
}
