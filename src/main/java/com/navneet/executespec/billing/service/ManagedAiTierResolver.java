package com.navneet.executespec.billing.service;

import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class ManagedAiTierResolver {

  private static final Map<String, Integer> TIER_RANK =
      Map.of(
          "FREE", 0,
          "PRO", 1,
          "PRO_PLUS", 2,
          "TEAM", 3,
          "BYOK", 3,
          "MAX", 3,
          "ENTERPRISE", 4);

  private final AppUserRepository userRepository;
  private final OrgRepository orgRepository;

  String resolveUserTier(String userId) {
    if (userId == null) return "FREE";
    AppUser user = userRepository.findById(userId).orElse(null);
    if (user == null) return "FREE";
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return "ENTERPRISE";
    return resolveEffectiveTier(user);
  }

  /**
   * RR-226 (2026-07-20): managed-AI billing policy is resolved from the user's OWN billing
   * relationship (personal tier, raised only by the org they actually belong to). External
   * host-project tiers are deliberately NOT folded in here.
   *
   * <p>Previously the highest tier of any external host project raised the user's global managed-AI
   * tier. Because TEAM/BYOK/ENTERPRISE policies carry zero managed caps and are therefore treated
   * as unmanaged/unlimited, a FREE or PRO user accepted onto a single external TEAM/Enterprise
   * project gained uncapped platform-key spend EVERYWHERE — including their own personal org and
   * projects — while still holding a personal billing relationship. That converted a scoped
   * collaboration entitlement into open-ended provider spend.
   *
   * <p>Feature entitlement (what the collaborator may DO inside the host project) still flows
   * through {@code EntitlementResolver}/{@code TierEnforcementService}; only the managed-credit
   * PAYER decision is narrowed here. Scoping the host tier to calls actually executing under the
   * host project's payer identity requires the execution context's payer org/project, which this
   * user-keyed resolver does not receive.
   */
  private String resolveEffectiveTier(AppUser user) {
    String effective = normalizeTier(user.getTier());
    if (user.getOrgId() != null) {
      Org org = orgRepository.findById(user.getOrgId()).orElse(null);
      if (org != null && org.getTier() != null) {
        effective = higherTier(effective, normalizeTier(org.getTier()));
      }
    }
    return effective;
  }

  private static String normalizeTier(String tier) {
    if (tier == null || tier.isBlank()) return "FREE";
    String normalized = tier.trim().toUpperCase();
    return "MAX".equals(normalized) ? "TEAM" : normalized;
  }

  private static String higherTier(String left, String right) {
    return TIER_RANK.getOrDefault(right, 0) > TIER_RANK.getOrDefault(left, 0) ? right : left;
  }
}
