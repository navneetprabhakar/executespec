package com.navneet.executespec.billing.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the pure state-machine logic in {@link TierLifecycleService}.
 *
 * <p>Instantiates the service with {@code null} repos because {@code validateTransition} does not
 * touch them. Tests that cover the repo + audit-writing path live in {@code
 * TierLifecycleServiceIntegrationTest} (to be added in Day 2+ when the admin controller lands).
 */
class TierLifecycleServiceTest {

  // Pure state-machine tests — validateTransition never touches repos,
  // gateway router, or feature flags. Null constructor args are safe here.
  private final TierLifecycleService svc =
      new TierLifecycleService(null, null, null, null, null, null, null);

  @Nested
  class AllowedTransitions {

    @Test
    void listedToHidden_isAllowed() {
      // just reaches the end without throwing
      svc.validateTransition(LifecycleState.LISTED, LifecycleState.HIDDEN, null);
    }

    @Test
    void hiddenToListed_isAllowed() {
      svc.validateTransition(LifecycleState.HIDDEN, LifecycleState.LISTED, Instant.now());
    }

    @Test
    void sunsettingToHidden_isAllowed() {
      svc.validateTransition(LifecycleState.SUNSETTING, LifecycleState.HIDDEN, Instant.now());
    }

    @Test
    void hiddenToSunsetting_isAllowed_after14Days() {
      Instant hiddenSince = Instant.now().minus(Duration.ofDays(15));
      svc.validateTransition(LifecycleState.HIDDEN, LifecycleState.SUNSETTING, hiddenSince);
    }
  }

  @Nested
  class RejectedTransitions {

    @Test
    void listedToSunsetting_isRejected() {
      assertThatThrownBy(
              () -> svc.validateTransition(LifecycleState.LISTED, LifecycleState.SUNSETTING, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("LISTED → SUNSETTING");
    }

    @Test
    void sunsettingToListed_isRejected() {
      assertThatThrownBy(
              () ->
                  svc.validateTransition(
                      LifecycleState.SUNSETTING, LifecycleState.LISTED, Instant.now()))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("SUNSETTING → LISTED");
    }

    @Test
    void sameStateTransition_isRejected() {
      assertThatThrownBy(
              () -> svc.validateTransition(LifecycleState.LISTED, LifecycleState.LISTED, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("Already in state");
    }

    @Test
    void hiddenToSunsetting_withNullChangedAt_isRejected() {
      assertThatThrownBy(
              () -> svc.validateTransition(LifecycleState.HIDDEN, LifecycleState.SUNSETTING, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("observation window");
    }

    @Test
    void hiddenToSunsetting_before14Days_isRejected() {
      Instant hiddenSince = Instant.now().minus(Duration.ofDays(3));
      assertThatThrownBy(
              () ->
                  svc.validateTransition(
                      LifecycleState.HIDDEN, LifecycleState.SUNSETTING, hiddenSince))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("day(s) remaining");
    }
  }

  @Nested
  class DbValueParsing {

    @Test
    void nullDbValue_parsesToListed() {
      assertThat(LifecycleState.fromDbValue(null)).isEqualTo(LifecycleState.LISTED);
    }

    @Test
    void unknownDbValue_parsesToListed() {
      assertThat(LifecycleState.fromDbValue("GARBAGE")).isEqualTo(LifecycleState.LISTED);
    }

    @Test
    void validDbValues_parseCorrectly() {
      assertThat(LifecycleState.fromDbValue("LISTED")).isEqualTo(LifecycleState.LISTED);
      assertThat(LifecycleState.fromDbValue("HIDDEN")).isEqualTo(LifecycleState.HIDDEN);
      assertThat(LifecycleState.fromDbValue("SUNSETTING")).isEqualTo(LifecycleState.SUNSETTING);
    }
  }

  /**
   * RR-352 — a SUNSETTING transition used to commit the tier state first and then no-op its side
   * effects whenever the feature flag was off or billing deps were missing, producing a tier that
   * reads as sunsetting while every subscription renews forever. Capability is now required BEFORE
   * the state change.
   */
  @Nested
  class SunsettingCapabilityGate {

    @Test
    void rejectsSunsetWhenSubscriberCancellationIsDisabled() {
      assertThatThrownBy(() -> svc.requireSunsettingCapability("PRO"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("billing.lifecycle.sunsetting.enabled");
    }

    @Test
    void rejectsSunsetWhenBillingDependenciesAreUnavailable() {
      TierLifecycleService withFlagOn =
          new TierLifecycleService(
              null,
              null,
              null,
              org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
              org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
              flagProviderReturning(true),
              null);

      assertThatThrownBy(() -> withFlagOn.requireSunsettingCapability("PRO"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("billing dependencies are unavailable");
    }

    @SuppressWarnings("unchecked")
    private org.springframework.beans.factory.ObjectProvider<
            com.navneet.executespec.config.featureflag.FeatureFlagService>
        flagProviderReturning(boolean enabled) {
      var flags =
          org.mockito.Mockito.mock(
              com.navneet.executespec.config.featureflag.FeatureFlagService.class);
      org.mockito.Mockito.when(flags.isEnabled(TierLifecycleService.SUNSETTING_ENABLED_FLAG))
          .thenReturn(enabled);
      var provider =
          (org.springframework.beans.factory.ObjectProvider<
                  com.navneet.executespec.config.featureflag.FeatureFlagService>)
              org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
      org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(flags);
      return provider;
    }
  }
}
