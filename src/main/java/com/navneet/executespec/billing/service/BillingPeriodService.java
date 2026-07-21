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

  /** Canonical annual period value. */
  public static final String ANNUAL = "ANNUAL";

  /**
   * True when {@code period} denotes an annual billing cycle.
   *
   * <p><b>RR-418.</b> Every annual plan migration (V146, V177) inserted {@code 'YEARLY'}, while all
   * runtime logic — this class, {@code RazorpayGateway}'s renewal count, and {@code
   * PlanSeederService}'s gateway period — recognised only {@code 'ANNUAL'} and silently fell
   * through to monthly. An annual customer therefore paid annual pricing while their local
   * entitlement expired after 30 days and Razorpay provisioned a monthly plan. V310 forward-migrates
   * the stored rows to {@code ANNUAL}; this alias keeps any row that predates or bypasses that
   * migration (a restored dump, a hand-inserted row) from silently reverting to monthly semantics.
   */
  public static boolean isAnnual(String period) {
    String normalized = period == null ? "" : period.trim().toUpperCase();
    return ANNUAL.equals(normalized) || "YEARLY".equals(normalized);
  }

  public Instant periodEnd(String period) {
    return isAnnual(period)
        ? now().plus(365, ChronoUnit.DAYS)
        : now().plus(30, ChronoUnit.DAYS);
  }

  /**
   * Start of the spend window reported on billing status.
   *
   * <p>RR-418 follow-on: this always subtracted 30 days from the period end, so an ANNUAL
   * subscriber's reported spend covered only the last month of a twelve-month period. When the
   * subscription carries a real {@code currentPeriodStart}, that is the period and is used
   * directly; the 30-day subtraction survives only as the fallback for rows that never recorded a
   * start.
   */
  public Instant statusSpendWindowStart(BillingSubscription subscription) {
    if (subscription != null && subscription.getCurrentPeriodStart() != null) {
      return subscription.getCurrentPeriodStart();
    }
    if (subscription != null && subscription.getCurrentPeriodEnd() != null) {
      return subscription.getCurrentPeriodEnd().minus(30, ChronoUnit.DAYS);
    }
    return now().minus(30, ChronoUnit.DAYS);
  }

  private Instant now() {
    return clock.instant();
  }
}
