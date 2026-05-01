package com.navneet.executespec.v3.entitlements;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.v3.credit.LifetimeCreditService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-142 — single-source resolver for v3 entitlements.
 *
 * <p>The strict-review case: org tier ENTERPRISE everywhere in the API, but the billing page showed
 * "Free / 30 of 5 runs / 286% over soft budget". Three sources of truth disagreed. This resolver
 * collapses tier + plan + caps + period usage into one read so v3 surfaces all consult the same
 * answer.
 *
 * <p>Tier resolution: pick the higher rank between the user's org tier (when orgId is set) and the
 * user's personal tier — whichever grants more capability wins. The audit on 2026-05-01 (F-14)
 * surfaced the previous "orgs always win" rule producing FREE for paid users whose backing org row
 * still said FREE: operator- promoted accounts, expired-but-not-demoted subscriptions, and test
 * fixtures whose `app_user.tier` was bumped without a matching `org.tier` move all rendered as FREE
 * on /billing while the legacy `/api/auth/me` correctly reported the paid tier. Picking the higher
 * rank closes that class of bug at the resolver instead of patching every consumer with
 * `canonicalTier(meTier, ent.tierKey)`.
 *
 * <p>Limits / caps: - Read from tier_config row by tier key. Falls back to {-1, -1, ...} when
 * missing (interpreted as unlimited downstream).
 *
 * <p>Display name normalisation: - MAX (legacy) → "Team" per V141 path + greenfield doc §2.6.
 *
 * <p>Cohabitation: v2 keeps reading from TierEnforcementService / billing_plan / monthly counters
 * as before; v3 reads exclusively from this resolver. Both are safe to coexist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementResolver {

  /**
   * Anything at or above this threshold is treated as the "unlimited sentinel" leaking through
   * (e.g. Integer.MAX_VALUE from {@link LifetimeCreditService#currentBalance}). The same threshold
   * lives on the frontend at {@code frontend/src/v3/lib/tierDisplay.ts MAX_DISPLAYABLE_BALANCE}.
   */
  static final int MAX_DISPLAYABLE_BALANCE = 1_000_000;

  /**
   * Capability rank for the higher-of-(org,user) pick in {@link #resolveTier}. MAX is treated as
   * TEAM-equivalent because it normalises to TEAM at the v3 surface (V141). Unknown tiers map to 0
   * so a typo can never out-rank a real tier.
   */
  private static final Map<String, Integer> TIER_RANK =
      Map.of(
          "FREE", 0,
          "PRO", 1,
          "TEAM", 2,
          "MAX", 2,
          "ENTERPRISE", 3);

  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;
  private final TierConfigRepository tierConfigRepo;
  private final LifetimeCreditService lifetimeCredit;

  /** Resolve entitlements for the given user. */
  public EntitlementsResponse resolve(String userId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
    String tierKey = resolveTier(user);
    TierConfig cfg = tierConfigRepo.findById(tierKey).orElse(null);

    String displayName = displayNameFor(tierKey, cfg);
    int monthlyRunLimit =
        cfg != null ? cfg.getMonthlyRunLimit() : (tierKey.equals("FREE") ? 5 : -1);
    int projectsLimit = cfg != null ? cfg.getMaxProjectsPerOrg() : -1;
    int reposLimit = cfg != null ? cfg.getMaxReposPerProject() : -1;
    // Storage cap is plan-level rather than tier-level today; expose
    // -1 here until we wire billing_plan.limits in a follow-up.
    long storageMb = -1;

    boolean unlimited = monthlyRunLimit == -1;
    Double softBudget = unlimited ? null : derivedSoftBudget(monthlyRunLimit);
    Double hardCap = unlimited ? null : softBudget != null ? softBudget * 1.5 : null;

    int currentRunCount = user.getPersonalRunCount();
    Double currentSpend = null; // Wave 2 follow-up — needs join with run_cost_log.

    // Task 1 (2026-05-01 fix list): {@link LifetimeCreditService#currentBalance}
    // returns Integer.MAX_VALUE for paid users as an internal "unlimited"
    // sentinel — that value must never leave the API. Two layers of guard:
    // 1) Decide "is the user effectively paid" by ORing the org-resolved
    //    tierKey AND the personal user.tier. The previous gate looked at
    //    only the org-resolved tier — a PRO user sitting under a
    //    personal-org with tier=FREE fell through to the FREE branch and
    //    leaked MAX_VALUE because the credit service still saw user.tier=PRO.
    // 2) Even if the FREE branch fires, clamp the response to -1 when the
    //    sentinel is detected so a regression in currentBalance() can't
    //    re-introduce the leak.
    boolean effectivePaid =
        !"FREE".equalsIgnoreCase(tierKey)
            || !"FREE".equalsIgnoreCase(user.getTier() != null ? user.getTier() : "FREE");
    int rawBalance = effectivePaid ? -1 : lifetimeCredit.currentBalance(userId);
    int lifetimeBalance = rawBalance >= MAX_DISPLAYABLE_BALANCE ? -1 : rawBalance;
    int lifetimeCap = effectivePaid ? -1 : user.getLifetimeRunCreditCap();

    return new EntitlementsResponse(
        tierKey,
        displayName,
        unlimited,
        monthlyRunLimit,
        projectsLimit,
        storageMb,
        softBudget,
        hardCap,
        currentRunCount,
        currentSpend,
        /* billingCycleAnchor */ null,
        lifetimeBalance,
        lifetimeCap);
  }

  /**
   * Tier key — picks the higher rank between user.tier and org.tier. See class doc for the
   * rationale.
   */
  String resolveTier(AppUser user) {
    String userTier = normalize(user.getTier() != null ? user.getTier() : "FREE");
    String orgTier = userTier;
    if (user.getOrgId() != null) {
      Optional<Org> org = orgRepo.findById(user.getOrgId());
      if (org.isPresent() && org.get().getTier() != null) {
        orgTier = normalize(org.get().getTier());
      }
    }
    int orgRank = TIER_RANK.getOrDefault(orgTier, 0);
    int userRank = TIER_RANK.getOrDefault(userTier, 0);
    return orgRank >= userRank ? orgTier : userTier;
  }

  static String normalize(String tier) {
    if (tier == null) return "FREE";
    String upper = tier.trim().toUpperCase();
    if (upper.isEmpty()) return "FREE";
    // V141 path: MAX collapses into TEAM at the v3 surface.
    if ("MAX".equals(upper)) return "TEAM";
    return upper;
  }

  static String displayNameFor(String tierKey, TierConfig cfg) {
    if (cfg != null && cfg.getDisplayName() != null && !cfg.getDisplayName().isBlank()) {
      return cfg.getDisplayName();
    }
    return switch (tierKey) {
      case "FREE" -> "Free";
      case "PRO" -> "Pro";
      case "TEAM", "MAX" -> "Team";
      case "ENTERPRISE" -> "Enterprise";
      default -> tierKey;
    };
  }

  /**
   * Heuristic soft budget for non-unlimited tiers — replaces the v2 hard-coded $10 banner. ~$2 per
   * allowed run.
   */
  static Double derivedSoftBudget(int monthlyRunLimit) {
    if (monthlyRunLimit <= 0) return null;
    return monthlyRunLimit * 2.0;
  }
}
