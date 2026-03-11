package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Enforces monthly run quotas.
 *
 * Limit resolution order:
 *   1. ENTERPRISE org with api_keys_provided = true  → unlimited (-1)
 *   2. Org with monthly_run_limit_override set       → use override
 *   3. Org tier default from tier_config table       → use tier default
 *   4. Free user personal quota from tier_config     → use 'FREE' row
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TierEnforcementService {

    private final AppUserRepository userRepo;
    private final OrgRepository orgRepo;
    private final TierConfigRepository tierConfigRepo;

    /** Safe fallback limits if tier_config rows are missing. */
    private static final int FALLBACK_FREE = 5;
    private static final int FALLBACK_PRO  = 50;
    private static final int FALLBACK_ENT  = 5000;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Assert the given user can submit another run. Throws 429 if quota exceeded. */
    public void assertCanSubmitRun(String userId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        // SUPER_ADMIN has no limit
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        if (user.getOrgId() != null) {
            Org org = orgRepo.findById(user.getOrgId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Org not found"));
            assertOrgQuota(org);
        } else {
            assertUserQuota(user);
        }
    }

    /** Increment run counter after a run is accepted. */
    public void incrementRunCount(String userId) {
        AppUser user = userRepo.findById(userId).orElse(null);
        if (user == null) return;

        if (user.getOrgId() != null) {
            orgRepo.findById(user.getOrgId()).ifPresent(org -> {
                maybeResetOrgCounter(org);
                org.setMonthlyRunCount(org.getMonthlyRunCount() + 1);
                orgRepo.save(org);
            });
        } else {
            maybeResetUserCounter(user);
            user.setPersonalRunCount(user.getPersonalRunCount() + 1);
            userRepo.save(user);
        }
    }

    // ── Limit resolution ─────────────────────────────────────────────────────

    /**
     * Effective monthly limit for an org:
     *  - ENTERPRISE + api_keys_provided → unlimited
     *  - override set                   → override value
     *  - else                           → tier_config default
     */
    public int effectiveOrgLimit(Org org) {
        // Use the org's actual tier. Payment verification gates tier *upgrades*
        // (handled at the billing layer), not run quota enforcement.
        String effectiveTier = org.getTier() != null ? org.getTier() : "FREE";

        // Enterprise with own keys = unlimited
        if ("ENTERPRISE".equals(effectiveTier) && org.isApiKeysProvided()) return -1;

        // Per-org override
        if (org.getMonthlyRunLimitOverride() != null) return org.getMonthlyRunLimitOverride();

        // Tier default from DB
        return getMonthlyLimit(effectiveTier);
    }

    /** Effective monthly limit for a solo FREE user. */
    public int effectiveUserLimit(AppUser user) {
        return getMonthlyLimit(user.getTier());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void assertUserQuota(AppUser user) {
        int limit = getMonthlyLimit(user.getTier());
        if (limit < 0) return; // unlimited
        maybeResetUserCounter(user);
        if (user.getPersonalRunCount() >= limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Monthly run quota exceeded (" + limit + " runs). Upgrade your plan.");
        }
    }

    private void assertOrgQuota(Org org) {
        int limit = effectiveOrgLimit(org);
        if (limit < 0) return; // unlimited
        maybeResetOrgCounter(org);
        if (org.getMonthlyRunCount() >= limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Org monthly run quota exceeded (" + limit + " runs). Upgrade your plan.");
        }
    }

    private int getMonthlyLimit(String tier) {
        if (tier == null) return FALLBACK_FREE;
        return tierConfigRepo.findById(tier.toUpperCase())
                .map(TierConfig::getMonthlyRunLimit)
                .orElseGet(() -> switch (tier.toUpperCase()) {
                    case "ENTERPRISE" -> FALLBACK_ENT;
                    case "PRO"        -> FALLBACK_PRO;
                    default           -> FALLBACK_FREE;
                });
    }

    private void maybeResetUserCounter(AppUser user) {
        if (isNewMonth(user.getRunCountResetAt())) {
            user.setPersonalRunCount(0);
            user.setRunCountResetAt(Instant.now());
            userRepo.save(user);
        }
    }

    private void maybeResetOrgCounter(Org org) {
        if (isNewMonth(org.getRunCountResetAt())) {
            org.setMonthlyRunCount(0);
            org.setRunCountResetAt(Instant.now());
            orgRepo.save(org);
        }
    }

    private boolean isNewMonth(Instant lastReset) {
        if (lastReset == null) return true;
        ZonedDateTime resetTime = lastReset.atZone(ZoneOffset.UTC);
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        return now.getYear() > resetTime.getYear() ||
               now.getMonthValue() > resetTime.getMonthValue();
    }
}
