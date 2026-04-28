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

        int lifetimeBalance = "FREE".equalsIgnoreCase(tierKey)
                ? lifetimeCredit.currentBalance(userId)
                : -1;
        int lifetimeCap = "FREE".equalsIgnoreCase(tierKey)
                ? user.getLifetimeRunCreditCap()
                : -1;

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
