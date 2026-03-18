package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingPlanLimit;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.entity.BillingUsage;
import com.navneet.executespec.billing.exception.BillingException;
import com.navneet.executespec.billing.repository.BillingPlanLimitRepository;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.BillingUsageRepository;
import com.navneet.executespec.billing.repository.RunTopupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /**
     * Check if user is within limits for a given metric.
     * Throws BillingException if limit exceeded.
     * SUPER_ADMIN is always allowed (unlimited usage).
     *
     * Call this BEFORE performing the action (e.g., before starting a run).
     */
    public void assertWithinLimits(String userId, String metric) {
        // SUPER_ADMIN has no limits
        AppUser user = userRepo.findById(userId).orElse(null);
        if (user != null && "SUPER_ADMIN".equals(user.getGlobalRole())) return;
        BillingSubscription sub = subscriptionRepo.findActiveByUserId(userId).orElse(null);

        // Resolve plan ID — fall back to FREE plan for users who registered before billing system
        UUID planId;
        if (sub != null) {
            planId = sub.getBillingPlanId();
        } else {
            BillingPlan freePlan = planRepo.findByTierAndActiveTrue("FREE")
                    .stream().findFirst().orElse(null);
            if (freePlan == null) {
                log.warn("No active FREE plan found and no subscription for user {} — allowing action", userId);
                return;
            }
            planId = freePlan.getId();
            log.debug("No subscription for user {} — using FREE plan limits as fallback", userId);
        }

        Optional<BillingPlanLimit> limitOpt = limitRepo.findByBillingPlanIdAndMetric(planId, metric);
        if (limitOpt.isEmpty()) {
            // No limit defined for this metric on this plan — allow
            return;
        }

        long maxValue = limitOpt.get().getMaxValue();
        if (maxValue <= 0) return; // unlimited

        Instant now = Instant.now();
        BillingUsage usage = getOrCreateUsage(userId, sub.getOrgId(), metric, now);

        if (usage.getCurrentValue() >= maxValue) {
            // For RUNS metric, check bonus top-up credits before rejecting
            if ("RUNS".equals(metric)) {
                Instant checkNow = Instant.now();
                long userBonus = runTopupRepo.sumRemainingByUserId(userId, checkNow);
                String orgId = sub != null ? sub.getOrgId() : null;
                long orgBonus = orgId != null ? runTopupRepo.sumRemainingByOrgId(orgId, checkNow) : 0;
                if (userBonus + orgBonus > 0) {
                    log.debug("RUNS limit reached but user {} has {} bonus runs (user={}, org={})",
                            userId, userBonus + orgBonus, userBonus, orgBonus);
                    return; // allow — bonus consumption handled by TierEnforcementService
                }
            }
            log.warn("Usage limit reached: user={}, metric={}, current={}, max={}",
                    userId, metric, usage.getCurrentValue(), maxValue);
            throw new BillingException(
                    String.format("%s limit reached (%d/%d). Upgrade your plan for more.",
                            metric, usage.getCurrentValue(), maxValue));
        }
    }

    /**
     * Increment usage counter for a metric.
     * Call this AFTER the action succeeds (e.g., after run is submitted).
     */
    @Transactional
    public void increment(String userId, String metric, long delta) {
        BillingSubscription sub = subscriptionRepo.findActiveByUserId(userId).orElse(null);
        String orgId = sub != null ? sub.getOrgId() : null;

        Instant now = Instant.now();
        BillingUsage usage = getOrCreateUsage(userId, orgId, metric, now);

        usageRepo.incrementValue(usage.getId(), delta, now);

        log.debug("Usage incremented: user={}, metric={}, delta={}, newValue={}",
                userId, metric, delta, usage.getCurrentValue() + delta);
    }

    /**
     * Decrement usage counter for a metric (e.g., refund a failed run).
     * Will not go below zero.
     */
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

        log.info("Usage decremented (refund): user={}, metric={}, delta={}, newValue={}",
                userId, metric, actualDelta, usage.getCurrentValue() - actualDelta);
    }

    /**
     * Get current usage for all metrics for a user (for billing status display).
     */
    public List<BillingUsage> getCurrentUsage(String userId) {
        return usageRepo.findAllCurrentByUserId(userId, Instant.now());
    }

    /**
     * Get or create a BillingUsage record for the current billing period.
     * Period is calendar month (1st to 1st).
     */
    private BillingUsage getOrCreateUsage(String userId, String orgId, String metric, Instant now) {
        Optional<BillingUsage> existing = usageRepo.findCurrentByUserIdAndMetric(userId, metric, now);
        if (existing.isPresent()) return existing.get();

        // Create new usage record for current period
        ZonedDateTime zdt = now.atZone(ZoneId.of("Asia/Kolkata"));
        YearMonth ym = YearMonth.from(zdt);
        Instant periodStart = ym.atDay(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        Instant periodEnd = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();

        BillingUsage usage = BillingUsage.builder()
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
