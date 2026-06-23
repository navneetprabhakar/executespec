package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
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

  private String authTierFor(String planTier) {
    return switch (planTier) {
      case "STARTER" -> "FREE";
      case "PRO" -> "PRO";
      case "TEAM" -> "TEAM";
      case "ENTERPRISE" -> "ENTERPRISE";
      case "MAX" -> "TEAM";
      default -> "FREE";
    };
  }

  private String keySourceModeFor(String keyMode) {
    return switch (keyMode) {
      case "BYOK" -> "OWN_KEYS";
      case "MANAGED", "PLATFORM" -> "SYSTEM";
      default -> "HYBRID";
    };
  }
}
