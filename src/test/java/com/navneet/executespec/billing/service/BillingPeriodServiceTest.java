package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.navneet.executespec.billing.entity.BillingSubscription;

import org.junit.jupiter.api.Test;

class BillingPeriodServiceTest {

  private final Instant now = Instant.parse("2026-06-20T00:00:00Z");
  private final BillingPeriodService service =
      new BillingPeriodService(Clock.fixed(now, ZoneOffset.UTC));

  @Test
  void periodEndUsesAnnualWindowForAnnualPlans() {
    assertThat(service.periodEnd("ANNUAL")).isEqualTo(now.plusSeconds(365L * 24 * 60 * 60));
  }

  @Test
  void periodEndDefaultsToMonthlyWindowForMonthlyBlankOrUnknownPlans() {
    assertThat(service.periodEnd("MONTHLY")).isEqualTo(now.plusSeconds(30L * 24 * 60 * 60));
    assertThat(service.periodEnd(null)).isEqualTo(now.plusSeconds(30L * 24 * 60 * 60));
    assertThat(service.periodEnd("WEEKLY")).isEqualTo(now.plusSeconds(30L * 24 * 60 * 60));
  }

  @Test
  void statusSpendWindowStartUsesCurrentPeriodEndWhenAvailable() {
    BillingSubscription subscription = new BillingSubscription();
    subscription.setCurrentPeriodEnd(Instant.parse("2026-07-01T00:00:00Z"));

    assertThat(service.statusSpendWindowStart(subscription))
        .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
  }

  @Test
  void statusSpendWindowStartFallsBackToLastThirtyDays() {
    assertThat(service.statusSpendWindowStart(null))
        .isEqualTo(Instant.parse("2026-05-21T00:00:00Z"));
    assertThat(service.statusSpendWindowStart(new BillingSubscription()))
        .isEqualTo(Instant.parse("2026-05-21T00:00:00Z"));
  }
}
