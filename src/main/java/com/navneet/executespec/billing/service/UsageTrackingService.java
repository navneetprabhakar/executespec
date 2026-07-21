package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingPlanLimit;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.entity.BillingUsage;
import com.navneet.executespec.billing.exception.BillingException;
import com.navneet.executespec.billing.repository.*;
import com.navneet.executespec.entitlements.EntitlementResolver;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageTrackingService {

  private final BillingUsageRepository usageRepo;
  private final BillingSubscriptionRepository subscriptionRepo;
  private final BillingPlanLimitRepository limitRepo;
  private final BillingPlanRepository planRepo;
  private final RunTopupRepository runTopupRepo;
  private final AppUserRepository userRepo;
  private final EntitlementResolver entitlementResolver;
  private final TierConfigRepository tierConfigRepo;

  /**
   * Check if user is within limits for a given metric. Throws BillingException if limit exceeded.
   * SUPER_ADMIN is always allowed (unlimited usage).
   *
   * <p>Call this BEFORE performing the action (e.g., before starting a run).
   *
   * <p><b>RR-186 (2026-07-20):</b> this method is a NON-ATOMIC read. Pairing it with a later {@link
   * #increment} call leaves a window in which N concurrent callers each observe {@code max - 1} and
   * are all admitted. Any surface that must actually ENFORCE a ceiling has to call {@link
   * #reserveWithinLimits} instead, which admits and increments in one conditional statement. This
   * read-only form is retained only for advisory/display use.
   */
  public void assertWithinLimits(String userId, String metric) {
    assertWithinLimits(userId, metric, false);
  }

  /**
   * RR-186 — atomic admission. Resolves the same allowance {@link #assertWithinLimits} would, then
   * increments the counter only if the post-increment value still fits, using a single conditional
   * database statement. Throws {@link BillingException} when the reservation loses.
   *
   * <p>Unmetered outcomes (SUPER_ADMIN, unlimited sentinel, no configured limit, bonus top-up
   * headroom) still record the usage, because the counter also feeds analytics and billing display.
   */
  @Transactional
  public void reserveWithinLimits(String userId, String metric, long delta) {
    admit(userId, metric, Math.max(0L, delta), true);
  }

  private void assertWithinLimits(String userId, String metric, boolean atomicReserve) {
    admit(userId, metric, atomicReserve ? 1L : 0L, atomicReserve);
  }

  /**
   * Shared admission. When {@code reserve} is false this is the historical read-only check. When it
   * is true the ceiling test and the counter increment happen in ONE conditional UPDATE, so no two
   * concurrent callers can both be admitted at the final unit of the allowance.
   */
  private void admit(String userId, String metric, long delta, boolean reserve) {
    // SUPER_ADMIN has no limits
    AppUser user = userRepo.findById(userId).orElse(null);
    if (user != null && "SUPER_ADMIN".equals(user.getGlobalRole())) {
      if (reserve && delta > 0) increment(userId, metric, delta);
      return;
    }
    BillingSubscription sub = subscriptionRepo.findActiveByUserId(userId).orElse(null);

    // Resolve plan ID — fall back to FREE plan for users who registered before billing system
    UUID planId;
    if (sub != null) {
      planId = sub.getBillingPlanId();
    } else {
      BillingPlan freePlan =
          planRepo.findByTierAndActiveTrue("FREE").stream().findFirst().orElse(null);
      if (freePlan == null) {
        log.warn(
            "No active FREE plan found and no subscription for user {} — allowing action", userId);
        if (reserve && delta > 0) increment(userId, metric, delta);
        return;
      }
      planId = freePlan.getId();
      log.debug("No subscription for user {} — using FREE plan limits as fallback", userId);
    }

    long maxValue;
    if ("RUNS".equals(metric) && user != null) {
      // Canonical RUNS quota source: tier_config keyed by the resolved tier
      // (higher-of org.tier and user.tier). The legacy billing_plan_limit
      // path stays in place for other metrics, but for RUNS it produced
      // false positives when org.tier had been promoted past
      // billing_subscription.billing_plan_id (e.g. ENTERPRISE org on a
      // stale FREE subscription row, or a still-pending payment webhook).
      String tierKey = entitlementResolver.resolveTier(user);
      TierConfig cfg = tierConfigRepo.findById(tierKey).orElse(null);
      if (cfg == null) {
        log.warn("No tier_config row for tier={} — allowing RUNS for user {}", tierKey, userId);
        if (reserve && delta > 0) increment(userId, metric, delta);
        return;
      }
      maxValue = cfg.getMonthlyRunLimit();
    } else {
      Optional<BillingPlanLimit> limitOpt = limitRepo.findByBillingPlanIdAndMetric(planId, metric);
      if (limitOpt.isEmpty()) {
        if (reserve && delta > 0) increment(userId, metric, delta);
        return;
      }
      maxValue = limitOpt.get().getMaxValue();
    }
    if (maxValue <= 0) { // unlimited sentinel
      if (reserve && delta > 0) increment(userId, metric, delta);
      return;
    }

    Instant now = Instant.now();
    String orgId = sub != null ? sub.getOrgId() : null;
    BillingUsage usage = getOrCreateUsage(userId, orgId, metric, now);

    // RR-186 — the ENFORCING path is one conditional statement: the row is incremented only if the
    // resulting value still fits the allowance, and the affected-row count is the admission answer.
    // Concurrent submissions therefore serialize on the row instead of all reading `max - 1`.
    boolean admitted =
        reserve && delta > 0
            ? usageRepo.incrementIfWithinAllowance(usage.getId(), delta, maxValue, now) > 0
            : usage.getCurrentValue() + Math.max(delta, 0L) <= maxValue;
    if (!admitted) {
      // For RUNS metric, check bonus top-up credits before rejecting
      if ("RUNS".equals(metric)) {
        Instant checkNow = Instant.now();
        long userBonus = runTopupRepo.sumRemainingByUserId(userId, checkNow);
        long orgBonus = orgId != null ? runTopupRepo.sumRemainingByOrgId(orgId, checkNow) : 0;
        if (userBonus + orgBonus > 0) {
          log.debug(
              "RUNS limit reached but user {} has {} bonus runs (user={}, org={})",
              userId,
              userBonus + orgBonus,
              userBonus,
              orgBonus);
          // allow — bonus consumption handled by TierEnforcementService
          if (reserve && delta > 0) increment(userId, metric, delta);
          return;
        }
      }
      log.warn(
          "Usage limit reached: user={}, metric={}, current={}, max={}",
          userId,
          metric,
          usage.getCurrentValue(),
          maxValue);
      throw new BillingException(
          String.format(
              "%s limit reached (%d/%d). Upgrade your plan for more.",
              metric, usage.getCurrentValue(), maxValue));
    }
  }

  /**
   * Increment usage counter for a metric. Call this AFTER the action succeeds (e.g., after run is
   * submitted).
   */
  @Transactional
  public void increment(String userId, String metric, long delta) {
    BillingSubscription sub = subscriptionRepo.findActiveByUserId(userId).orElse(null);
    String orgId = sub != null ? sub.getOrgId() : null;

    Instant now = Instant.now();
    BillingUsage usage = getOrCreateUsage(userId, orgId, metric, now);

    usageRepo.incrementValue(usage.getId(), delta, now);

    log.debug(
        "Usage incremented: user={}, metric={}, delta={}, newValue={}",
        userId,
        metric,
        delta,
        usage.getCurrentValue() + delta);
  }

  /** Decrement usage counter for a metric (e.g., refund a failed run). Will not go below zero. */
  @Transactional
  public void decrement(String userId, String metric, long delta) {
    BillingSubscription sub = subscriptionRepo.findActiveByUserId(userId).orElse(null);

    Instant now = Instant.now();
    Optional<BillingUsage> usageOpt = usageRepo.findCurrentByUserIdAndMetric(userId, metric, now);
    if (usageOpt.isEmpty()) return; // nothing to decrement

    BillingUsage usage = usageOpt.get();
    long actualDelta = Math.min(delta, usage.getCurrentValue()); // don't go below 0
    if (actualDelta <= 0) return;

    usageRepo.incrementValue(usage.getId(), -actualDelta, now);

    log.info(
        "Usage decremented (refund): user={}, metric={}, delta={}, newValue={}",
        userId,
        metric,
        actualDelta,
        usage.getCurrentValue() - actualDelta);
  }

  /** Get current usage for all metrics for a user (for billing status display). */
  public List<BillingUsage> getCurrentUsage(String userId) {
    return usageRepo.findAllCurrentByUserId(userId, Instant.now());
  }

  /**
   * Get or create a BillingUsage record for the current billing period. Period is calendar month
   * (1st to 1st).
   */
  private BillingUsage getOrCreateUsage(String userId, String orgId, String metric, Instant now) {
    Optional<BillingUsage> existing = usageRepo.findCurrentByUserIdAndMetric(userId, metric, now);
    if (existing.isPresent()) return existing.get();

    // Create new usage record for current period
    ZonedDateTime zdt = now.atZone(ZoneId.of("Asia/Kolkata"));
    YearMonth ym = YearMonth.from(zdt);
    Instant periodStart = ym.atDay(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
    Instant periodEnd =
        ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();

    BillingUsage usage =
        BillingUsage.builder()
            .userId(userId)
            .orgId(orgId)
            .metric(metric)
            .currentValue(0)
            .periodStart(periodStart)
            .periodEnd(periodEnd)
            .lastUpdated(now)
            .build();

    return usageRepo.save(usage);
  }
}
