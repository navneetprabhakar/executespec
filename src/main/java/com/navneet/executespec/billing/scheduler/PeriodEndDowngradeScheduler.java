package com.navneet.executespec.billing.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Downgrades cancelled subscriptions to FREE after the billing period ends.
 *
 * <p>Runs every 30 minutes. Finds all CANCELLED subscriptions whose {@code currentPeriodEnd} has
 * passed, then downgrades the associated user/org to FREE tier.
 *
 * <p>This ensures users retain access until their paid period expires (grace period) before being
 * downgraded.
 *
 * <p><b>RR-517 (paid-entitlement loss).</b> This job used to set the user (and optional org) to
 * FREE unconditionally for every expired CANCELLED row. Because the schema permits multiple
 * subscription rows per billing entity, a customer who cancelled one subscription and then
 * resubscribed or upgraded <i>before the old period ended</i> would have their newly-paid tier
 * erased the next time the stale row expired — losing access, seats and managed-AI entitlements
 * despite a valid current charge. The job now reconciles every live subscription for the billing
 * entity and downgrades only when no later paid source remains. The expired row is still marked
 * DOWNGRADED either way, so the job stays idempotent and does not re-examine the same row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeriodEndDowngradeScheduler {

  private final BillingSubscriptionRepository subRepo;
  private final BillingPlanRepository planRepo;
  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;

  @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
  public void handlePeriodEndDowngrades() {
    List<BillingSubscription> expired = subRepo.findCancelledPastPeriodEnd(Instant.now());
    if (expired.isEmpty()) return;

    log.info(
        "[PeriodEndDowngrade] Found {} cancelled subscriptions past period end", expired.size());

    for (BillingSubscription sub : expired) {
      try {
        downgradeIfNoRemainingPaidSource(sub);
      } catch (Exception e) {
        log.error(
            "[PeriodEndDowngrade] Failed to downgrade sub {}: {}", sub.getId(), e.getMessage());
      }
    }
  }

  void downgradeIfNoRemainingPaidSource(BillingSubscription sub) {
    BillingSubscription supersedingUserSub =
        firstPaidSource(subRepo.findOtherLiveByUserId(sub.getUserId(), sub.getId()));

    if (supersedingUserSub != null) {
      log.info(
          "[PeriodEndDowngrade] Skipping user downgrade for sub {} — user {} still holds paid"
              + " subscription {}",
          sub.getId(),
          sub.getUserId(),
          supersedingUserSub.getId());
    } else {
      userRepo
          .findById(sub.getUserId())
          .ifPresent(
              user -> {
                if (!"FREE".equals(user.getTier())) {
                  user.setTier("FREE");
                  userRepo.save(user);
                  log.info("[PeriodEndDowngrade] Downgraded user {} to FREE", user.getEmail());
                }
              });
    }

    // Downgrade org if subscription is org-level — same reconciliation, org-scoped.
    if (sub.getOrgId() != null) {
      BillingSubscription supersedingOrgSub =
          firstPaidSource(subRepo.findOtherLiveByOrgId(sub.getOrgId(), sub.getId()));
      if (supersedingOrgSub != null) {
        log.info(
            "[PeriodEndDowngrade] Skipping org downgrade for sub {} — org {} still holds paid"
                + " subscription {}",
            sub.getId(),
            sub.getOrgId(),
            supersedingOrgSub.getId());
      } else {
        orgRepo
            .findById(sub.getOrgId())
            .ifPresent(
                org -> {
                  boolean changed = false;
                  if (!"FREE".equals(org.getTier())) {
                    org.setTier("FREE");
                    changed = true;
                  }
                  // A retained OWN_KEYS/HYBRID mode would keep customer credentials dispatchable
                  // after the BYOK entitlement ends. Repair historical stale FREE rows too; stored
                  // secrets remain recoverable if the customer upgrades again.
                  if (!"SYSTEM".equals(org.getKeySourceMode())) {
                    org.setKeySourceMode("SYSTEM");
                    changed = true;
                  }
                  if (changed) {
                    orgRepo.save(org);
                    log.info("[PeriodEndDowngrade] Downgraded org {} to FREE", org.getName());
                  }
                });
      }
    }

    // Mark subscription as DOWNGRADED (terminal state)
    sub.setStatus("DOWNGRADED");
    sub.setUpdatedAt(Instant.now());
    subRepo.save(sub);
  }

  /**
   * The first still-valid paid subscription in {@code candidates}, or {@code null} when none
   * remains. A candidate counts only when its own period has not already lapsed — an equally stale
   * row must not indefinitely block the downgrade — and when its plan resolves to a paid tier under
   * the canonical {@link TierEnforcementService#isPaidTier} vocabulary.
   *
   * <p>A candidate whose plan row cannot be resolved is treated as paid: an unreadable plan is not
   * evidence that the customer stopped paying, and holding the tier is the recoverable failure.
   */
  private BillingSubscription firstPaidSource(List<BillingSubscription> candidates) {
    Instant now = Instant.now();
    for (BillingSubscription candidate : candidates) {
      if (candidate.getCurrentPeriodEnd() != null
          && candidate.getCurrentPeriodEnd().isBefore(now)) {
        continue;
      }
      UUID planId = candidate.getBillingPlanId();
      if (planId == null) {
        return candidate;
      }
      BillingPlan plan = planRepo.findById(planId).orElse(null);
      if (plan == null || TierEnforcementService.isPaidTier(plan.getTier())) {
        return candidate;
      }
    }
    return null;
  }
}
