package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.exception.BillingException;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingTierBridgeService {

  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;
  private final BillingPlanRepository planRepo;
  private final BillingSubscriptionRepository subRepo;
  private final BillingNotificationService notificationService;

  public void upgradeTier(String userId, String orgId, BillingPlan plan) {
    AppUser user = userRepo.findById(userId).orElseThrow();
    String authTier = authTierFor(plan.getTier());
    user.setTier(authTier);
    userRepo.save(user);

    if (orgId != null) {
      orgRepo
          .findById(orgId)
          .ifPresent(
              org -> {
                org.setKeySourceMode(keySourceModeFor(plan.getKeyMode()));
                org.setTier(authTier);
                orgRepo.save(org);
              });
    }

    log.info(
        "Tier upgraded: user={}, authTier={}, planTier={}, keyMode={}",
        userId,
        authTier,
        plan.getTier(),
        plan.getKeyMode());
  }

  public void downgradeToFree(BillingSubscription sub) {
    sub.setStatus("DOWNGRADED");
    sub.setDowngradedAt(java.time.Instant.now());
    subRepo.save(sub);

    AppUser user = userRepo.findById(sub.getUserId()).orElseThrow();
    user.setTier("FREE");
    userRepo.save(user);

    if (sub.getOrgId() != null) {
      orgRepo
          .findById(sub.getOrgId())
          .ifPresent(
              org -> {
                org.setKeySourceMode("SYSTEM");
                org.setTier("FREE");
                orgRepo.save(org);
              });
    }

    log.warn(
        "Downgraded to free: user={}, previousPlan={}", sub.getUserId(), sub.getBillingPlanId());

    BillingPlan downgradedPlan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
    notificationService.sendDowngradedToFree(
        user.getEmail(), downgradedPlan != null ? downgradedPlan.getName() : "");
  }

  /**
   * Translate a {@code billing_plan.tier} value into the canonical auth tier.
   *
   * <p>RR-410: this switch previously had no case for {@code PRO_PLUS} or {@code BYOK} — both
   * first-class paid tiers since V253 — and its {@code default} was {@code FREE}. Every successful
   * Razorpay verification and Stripe invoice activation runs through here, so a customer who paid
   * for Pro+ or BYOK was immediately persisted as FREE (and, for BYOK, left in the contradictory
   * {@code tier=FREE, keySourceMode=OWN_KEYS} state).
   *
   * <p>The mapping is now exhaustive against {@link TierEnforcementService#TIER_RANK} — the single
   * canonical tier vocabulary — and an unknown tier throws instead of silently degrading to FREE.
   * Failing the activation is loud and recoverable; silently granting FREE for a paid charge is
   * neither.
   */
  static String authTierFor(String planTier) {
    String normalized = planTier == null ? "" : planTier.trim().toUpperCase();
    return switch (normalized) {
        // Retired/legacy plan-tier spellings that still map onto a live auth tier.
      case "STARTER", "FREE" -> "FREE";
      case "MAX" -> "TEAM";
        // V253 canonical ladder.
      case "PRO" -> "PRO";
      case "PRO_PLUS" -> "PRO_PLUS";
      case "BYOK" -> "BYOK";
      case "TEAM" -> "TEAM";
      case "ENTERPRISE" -> "ENTERPRISE";
      default ->
          throw new BillingException(
              "Cannot activate subscription: unknown billing plan tier '"
                  + planTier
                  + "'. Refusing to persist a default entitlement for a paid plan.");
    };
  }

  static String keySourceModeFor(String keyMode) {
    String normalized = keyMode == null ? "" : keyMode.trim().toUpperCase();
    return switch (normalized) {
      case "BYOK" -> "OWN_KEYS";
      case "MANAGED", "PLATFORM" -> "SYSTEM";
      default -> "HYBRID";
    };
  }
}
