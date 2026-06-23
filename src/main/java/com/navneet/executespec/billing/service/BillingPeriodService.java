package com.navneet.executespec.billing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.navneet.executespec.billing.entity.BillingSubscription;

import org.springframework.stereotype.Service;

@Service
public class BillingPeriodService {

  private final Clock clock;

  public BillingPeriodService() {
    this(Clock.systemUTC());
  }

  BillingPeriodService(Clock clock) {
    this.clock = clock;
  }

  public Instant periodEnd(String period) {
    String normalized = period == null ? "" : period;
    return switch (normalized) {
      case "ANNUAL" -> now().plus(365, ChronoUnit.DAYS);
      default -> now().plus(30, ChronoUnit.DAYS);
    };
  }

  public Instant statusSpendWindowStart(BillingSubscription subscription) {
    if (subscription != null && subscription.getCurrentPeriodEnd() != null) {
      return subscription.getCurrentPeriodEnd().minus(30, ChronoUnit.DAYS);
    }
    return now().minus(30, ChronoUnit.DAYS);
  }

  private Instant now() {
    return clock.instant();
  }
}
