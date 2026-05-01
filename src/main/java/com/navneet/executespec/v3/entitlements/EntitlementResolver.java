package com.navneet.executespec.v3.entitlements;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.v3.credit.LifetimeCreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * R-142 — single-source resolver for v3 entitlements.
 *
 * The strict-review case: org tier ENTERPRISE everywhere in the API,
 * but the billing page showed "Free / 30 of 5 runs / 286% over soft
 * budget". Three sources of truth disagreed. This resolver collapses
 * tier + plan + caps + period usage into one read so v3 surfaces all
 * consult the same answer.
 *
 * Tier resolution order:
 *   1. The user's org tier (when orgId is set) — orgs always win.
 *   2. The user's personal tier (FREE for solo users).
 *   3. FREE as a safe fallback.
 *
 * Limits / caps:
 *   - Read from tier_config row by tier key. Falls back to {-1, -1, ...}
 *     when missing (interpreted as unlimited downstream).
 *
 * Display name normalisation:
 *   - MAX (legacy) → "Team" per V141 path + greenfield doc §2.6.
 *
 * Cohabitation: v2 keeps reading from TierEnforcementService /
 * billing_plan / monthly counters as before; v3 reads exclusively
 * from this resolver. Both are safe to coexist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementResolver {

    /** Anything at or above this threshold is treated as the
     *  "unlimited sentinel" leaking through (e.g. Integer.MAX_VALUE
     *  from {@link LifetimeCreditService#currentBalance}). The same
     *  threshold lives on the frontend at
     *  {@code frontend/src/v3/lib/tierDisplay.ts MAX_DISPLAYABLE_BALANCE}. */
    static final int MAX_DISPLAYABLE_BALANCE = 1_000_000;

    private final AppUserRepository userRepo;
    private final OrgRepository orgRepo;
    private final TierConfigRepository tierConfigRepo;
    private final LifetimeCreditService lifetimeCredit;

    /** Resolve entitlements for the given user. */
    public EntitlementsResponse resolve(String userId) {
        AppUser user = userRepo.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user not found: " + userId));
        String tierKey = resolveTier(user);
        TierConfig cfg = tierConfigRepo.findById(tierKey).orElse(null);

        String displayName = displayNameFor(tierKey, cfg);
        int monthlyRunLimit = cfg != null ? cfg.getMonthlyRunLimit() : (tierKey.equals("FREE") ? 5 : -1);
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
                        || !"FREE".equalsIgnoreCase(
                                user.getTier() != null ? user.getTier() : "FREE");
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
                lifetimeCap
        );
    }

    /** Tier key with org-wins semantics. */
    String resolveTier(AppUser user) {
        if (user.getOrgId() != null) {
            Optional<Org> org = orgRepo.findById(user.getOrgId());
            if (org.isPresent() && org.get().getTier() != null) {
                return normalize(org.get().getTier());
            }
        }
        return normalize(user.getTier() != null ? user.getTier() : "FREE");
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

    /** Heuristic soft budget for non-unlimited tiers — replaces the
     *  v2 hard-coded $10 banner. ~$2 per allowed run. */
    static Double derivedSoftBudget(int monthlyRunLimit) {
        if (monthlyRunLimit <= 0) return null;
        return monthlyRunLimit * 2.0;
    }
}
