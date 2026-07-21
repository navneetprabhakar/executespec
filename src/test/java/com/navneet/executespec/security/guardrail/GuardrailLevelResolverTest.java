package com.navneet.executespec.security.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.security.InputSanitizer;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GuardrailLevelResolverTest {

  @Test
  void missingValueFailsSafeToHighAndEmitsMetric() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(flags.getString(GuardrailLevelResolver.FLAG_KEY, null)).thenReturn(null);

    GuardrailLevelResolver resolver = new GuardrailLevelResolver(flags, meters);

    assertThat(resolver.current()).isEqualTo(GuardrailLevel.HIGH);
    assertThat(
            meters
                .get("executespec.security.guardrail.resolution_failures")
                .tag("reason", "missing_flag_value")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void invalidValueFailsSafeToHigh() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(flags.getString(GuardrailLevelResolver.FLAG_KEY, null)).thenReturn("off");

    assertThat(new GuardrailLevelResolver(flags, meters).current()).isEqualTo(GuardrailLevel.HIGH);
  }

  @Test
  void validValueIsPreserved() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.getString(GuardrailLevelResolver.FLAG_KEY, null)).thenReturn("MEDIUM");

    assertThat(new GuardrailLevelResolver(flags, new SimpleMeterRegistry()).current())
        .isEqualTo(GuardrailLevel.MEDIUM);
  }

  @Test
  void featureFlagOutageKeepsChatIngressAtHighInsteadOfFailingOpen() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(flags.getString(GuardrailLevelResolver.FLAG_KEY, null))
        .thenThrow(new IllegalStateException("Redis unavailable"));
    GuardrailLevelResolver resolver = new GuardrailLevelResolver(flags, meters);
    InputSanitizer sanitizer = new InputSanitizer(resolver);

    assertThatThrownBy(() -> sanitizer.scan("a".repeat(8_001)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("guardrail level HIGH");
    assertThat(
            meters
                .get("executespec.security.guardrail.resolution_failures")
                .tag("reason", "flag_read_failed")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1.0);
  }
}
