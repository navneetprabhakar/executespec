package com.navneet.executespec.billing.service;

import java.util.Map;

import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.exception.BillingException;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
class BillingSubscriptionPolicy {

  private static final Map<String, Integer> TIER_RANK =
      Map.of(
          "FREE", 0,
          "STARTER", 1,
          "PRO", 2,
          "PRO_PLUS", 3,
          "BYOK", 4,
          "TEAM", 4,
          "ENTERPRISE", 5);

  private final TierConfigRepository tierConfigRepository;

  boolean tierIsListed(String tier) {
    if (tier == null) return true;
    return tierConfigRepository
        .findById(tier)
        .map(tc -> "LISTED".equals(tc.getLifecycleState()))
        .orElse(true);
  }

  int effectiveSeats(BillingPlan plan, int requestedSeats) {
    if ("TEAM".equals(plan.getTier())) {
      if (requestedSeats < 2 || requestedSeats > 20) {
        throw new BillingException(
            "TEAM tier requires 2-20 seats (got "
                + requestedSeats
                + "). Contact sales for larger orgs.");
      }
      return requestedSeats;
    }
    if (requestedSeats != 1) {
      log.warn("seats={} ignored for non-TEAM tier={} - forcing 1", requestedSeats, plan.getTier());
    }
    return 1;
  }

  void requireNewSubscriptionAvailable(BillingPlan plan) {
    if (!plan.isActive()) {
      throw new BillingException("This plan is no longer available.");
    }
    boolean hiddenProPlus =
        "PRO_PLUS".equals(plan.getTier())
            && "HIDDEN".equals(plan.getLifecycleState())
            && !tierIsListed(plan.getTier());
    if ("SUNSETTING".equals(plan.getLifecycleState())
        || (!"LISTED".equals(plan.getLifecycleState()) && !hiddenProPlus)) {
      throw new BillingException("This plan is not available for new subscriptions.");
    }
    if (!tierIsListed(plan.getTier()) && !hiddenProPlus) {
      throw new BillingException("This plan is not available for new subscriptions.");
    }
    if ("FREE".equals(plan.getTier())) {
      throw new BillingException("Cannot subscribe to the free plan via checkout.");
    }
  }

  void requireUpgrade(String newTier, String existingTier) {
    if (!isStrictUpgrade(newTier, existingTier)) {
      throw new BillingException(
          "You already have an active "
              + existingTier
              + " subscription. Cancel it first to switch plans.");
    }
  }

  boolean isStrictUpgrade(String newTier, String existingTier) {
    return tierRank(newTier) > tierRank(existingTier);
  }

  boolean founderPricingAvailable(BillingPlan plan, long activeFounders, int founderMaxSlots) {
    if (!plan.isFounderPricing()) return false;
    if (activeFounders >= founderMaxSlots) {
      throw new BillingException("Founder pricing is no longer available (all slots taken).");
    }
    return true;
  }

  private static int tierRank(String tier) {
    return TIER_RANK.getOrDefault(tier, 0);
  }
}
