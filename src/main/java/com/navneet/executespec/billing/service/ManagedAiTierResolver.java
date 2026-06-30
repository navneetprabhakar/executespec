package com.navneet.executespec.billing.service;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;

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
  private final ProjectMemberRepository projectMemberRepository;

  String resolveUserTier(String userId) {
    if (userId == null) return "FREE";
    AppUser user = userRepository.findById(userId).orElse(null);
    if (user == null) return "FREE";
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return "ENTERPRISE";
    return resolveEffectiveTier(user);
  }

  private String resolveEffectiveTier(AppUser user) {
    String effective = normalizeTier(user.getTier());
    if (user.getOrgId() != null) {
      Org org = orgRepository.findById(user.getOrgId()).orElse(null);
      if (org != null && org.getTier() != null) {
        effective = higherTier(effective, normalizeTier(org.getTier()));
      }
    }
    for (String hostTier : externalHostTiers(user.getId())) {
      effective = higherTier(effective, normalizeTier(hostTier));
    }
    return effective;
  }

  private List<String> externalHostTiers(String userId) {
    List<String> tiers = projectMemberRepository.findActiveExternalHostOrgTiersByUserId(userId);
    return tiers != null ? tiers : List.of();
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
