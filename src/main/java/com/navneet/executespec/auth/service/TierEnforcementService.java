package com.navneet.executespec.auth.service;

import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.service.RunTopupService;
import com.navneet.executespec.harness.event.QuotaEvent;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

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
    private final RunTopupService runTopupService;
    private final BillingSubscriptionRepository subscriptionRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final ProjectRepository projectRepo;
    private final ProjectRepoLinkRepository projectRepoLinkRepo;

    /** Quota utilisation at which we emit a one-shot warning notification. */
    private static final double QUOTA_WARNING_THRESHOLD = 0.80;

    /** Safe fallback limits if tier_config rows are missing. */
    /** V140 — matches tier_config.FREE.monthly_run_limit; only used when DB lookup fails. */
    private static final int FALLBACK_FREE = 5;
    /** V140 — matches tier_config.PRO.monthly_run_limit (was 50, now 20 per the website). */
    private static final int FALLBACK_PRO  = 20;
    /** V141 (2026-04-26) — TEAM is the renamed MAX tier ($29/mo BYOK unlimited). The
     *  v2.6.1-Wave-1 per-seat "TEAM" tier was deleted in V141 along with this constant's
     *  predecessor (FALLBACK_TEAM_PER_SEAT). */
    private static final int FALLBACK_TEAM = -1;  // unlimited
    /** V140 — ENTERPRISE is unlimited per the website ("Unlimited (fair use)"). */
    private static final int FALLBACK_ENT  = -1;

    /** Default per-run cost budgets (USD). */
    private static final double DEFAULT_FREE_COST_BUDGET = 3.0;
    private static final double DEFAULT_PRO_COST_BUDGET  = 5.0;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Atomically checks quota AND increments the run count in a single transaction
     * with pessimistic locking. Prevents TOCTOU race where two concurrent submissions
     * both pass the quota check before either increments.
     * Throws 429/402 if quota exceeded.
     */
    @Transactional
    public void assertAndIncrementRunCount(String userId) {
        AppUser user = userRepo.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        if (user.getOrgId() != null) {
            Org org = orgRepo.findByIdForUpdate(user.getOrgId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Org not found"));
            assertOrgQuota(org);
            // Increment within the same TX (lock held)
            maybeResetOrgCounter(org);
            int orgBefore = org.getMonthlyRunCount();
            org.setMonthlyRunCount(orgBefore + 1);
            orgRepo.save(org);
            int limit = effectiveOrgLimit(org);
            if (limit >= 0 && org.getMonthlyRunCount() > limit) {
                runTopupService.consumeBonusRunForOrg(org.getId());
            }
            maybeFireQuotaWarning(userId, org.getId(), orgBefore, org.getMonthlyRunCount(), limit);
            return;
        }

        assertUserQuota(user);
        // Increment within the same TX (lock held)
        maybeResetUserCounter(user);
        int personalBefore = user.getPersonalRunCount();
        user.setPersonalRunCount(personalBefore + 1);
        userRepo.save(user);
        int limit = getMonthlyLimit(user.getTier());
        if (limit >= 0 && user.getPersonalRunCount() > limit) {
            runTopupService.consumeBonusRun(userId);
        }
        maybeFireQuotaWarning(userId, null, personalBefore, user.getPersonalRunCount(), limit);
    }

    /**
     * Atomically check quota and increment by N credits.
     * Used at plan approval when cost estimation determines multi-credit runs.
     */
    @Transactional
    public void assertAndIncrementRunCount(String userId, int credits) {
        if (credits <= 0) credits = 1;
        AppUser user = userRepo.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        if (user.getOrgId() != null) {
            Org org = orgRepo.findByIdForUpdate(user.getOrgId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Org not found"));
            assertOrgQuota(org);
            maybeResetOrgCounter(org);
            int orgBefore = org.getMonthlyRunCount();
            org.setMonthlyRunCount(orgBefore + credits);
            orgRepo.save(org);
            int limit = effectiveOrgLimit(org);
            if (limit >= 0 && org.getMonthlyRunCount() > limit) {
                for (int i = 0; i < credits; i++) {
                    try { runTopupService.consumeBonusRunForOrg(org.getId()); }
                    catch (Exception e) { break; }
                }
            }
            maybeFireQuotaWarning(userId, org.getId(), orgBefore, org.getMonthlyRunCount(), limit);
            return;
        }

        assertUserQuota(user);
        maybeResetUserCounter(user);
        int personalBefore = user.getPersonalRunCount();
        user.setPersonalRunCount(personalBefore + credits);
        userRepo.save(user);
        int limit = getMonthlyLimit(user.getTier());
        if (limit >= 0 && user.getPersonalRunCount() > limit) {
            for (int i = 0; i < credits; i++) {
                try { runTopupService.consumeBonusRun(userId); }
                catch (Exception e) { break; }
            }
        }
        maybeFireQuotaWarning(userId, null, personalBefore, user.getPersonalRunCount(), limit);
    }

    /**
     * Emit a one-shot {@link QuotaEvent.QuotaWarning} when a counter crosses
     * {@link #QUOTA_WARNING_THRESHOLD}. Safe to call with unlimited limits (-1) —
     * no-ops. Runs in the caller's transaction; publish is best-effort and swallows
     * exceptions so it never impacts the increment path.
     */
    private void maybeFireQuotaWarning(String userId, String orgId, int before, int after, int limit) {
        if (limit <= 0) return; // unlimited or invalid
        double threshold = limit * QUOTA_WARNING_THRESHOLD;
        if (before >= threshold || after < threshold) return;
        try {
            int percent = (int) Math.floor(100.0 * after / limit);
            eventPublisher.publishEvent(new QuotaEvent.QuotaWarning(
                    userId, orgId, "RUNS", after, limit, percent));
        } catch (Exception e) {
            log.warn("Failed to publish QuotaWarning for user={} org={}: {}",
                    userId, orgId, e.getMessage());
        }
    }

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

    /**
     * Assert the given user can apply a suggestion (chat copilot or review) to the given run.
     * Throws 429 if the per-run apply limit for the user's tier is exceeded.
     * SUPER_ADMIN and ENTERPRISE (limit=-1) are always allowed.
     */
    public void assertCanApplySuggestion(String userId, PipelineRun run) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        // SUPER_ADMIN has no limit
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        int limit = getApplySuggestionLimit(user);
        if (limit < 0) return; // unlimited (ENTERPRISE)

        if (run.getApplySuggestionCount() >= limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Apply limit reached (" + run.getApplySuggestionCount() + "/" + limit + "). Create a fresh run instead.");
        }
    }

    /**
     * V140 — Assert the caller can create another project under {@code orgId}.
     *
     * <p>Resolution order: SUPER_ADMIN bypass; otherwise look up the
     * effective tier (org tier if {@code orgId} is non-null, else
     * user.tier) and read {@code tier_config.max_projects_per_org}. If
     * the limit is non-negative and the org's current ACTIVE project
     * count meets or exceeds it, throws 403 with an upgrade hint.
     *
     * <p>Throws 401 if the user is unknown.
     */
    public void assertCanCreateProject(String userId, String orgId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        String tier = resolveTierForLimitCheck(user, orgId);
        int limit = tierConfigRepo.findById(tier)
                .map(TierConfig::getMaxProjectsPerOrg)
                .orElse(-1);
        if (limit < 0) return; // unlimited

        // Project.org_id is non-nullable in the schema, so org-level count
        // is the only meaningful scope. If orgId is null we conservatively
        // skip enforcement (the create call will fail at persist anyway).
        if (orgId == null) return;
        long current = projectRepo.countByOrgIdAndStatus(orgId, "ACTIVE");

        if (current >= limit) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tier " + tier + " allows " + limit + " project" + (limit == 1 ? "" : "s")
                            + " per org. Upgrade to TEAM for 10 or ENTERPRISE for unlimited.");
        }
    }

    /**
     * V140 — Assert the caller can link another repo to {@code projectId}.
     *
     * <p>Resolution order: SUPER_ADMIN bypass; otherwise look up the
     * effective tier (project's org tier if the project has an org, else
     * user.tier) and read {@code tier_config.max_repos_per_project}. If
     * the limit is non-negative and the project already has that many
     * (or more) repo links, throws 403 with an upgrade hint.
     *
     * <p>Throws 401 if the user is unknown, 404 if the project is unknown.
     */
    public void assertCanLinkRepoToProject(String userId, String projectId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        String tier = resolveTierForLimitCheck(user, project.getOrgId());
        int limit = tierConfigRepo.findById(tier)
                .map(TierConfig::getMaxReposPerProject)
                .orElse(-1);
        if (limit < 0) return; // unlimited

        long current = projectRepoLinkRepo.countByProjectId(projectId);
        if (current >= limit) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tier " + tier + " allows " + limit + " repo" + (limit == 1 ? "" : "s")
                            + " per project. Upgrade to TEAM for 10 or ENTERPRISE for unlimited.");
        }
    }

    /**
     * Resolves the effective tier used by the new project / repo limit
     * checks. Org tier wins when {@code orgId} is non-null; otherwise the
     * caller's user tier applies (this is the FREE-solo path).
     */
    private String resolveTierForLimitCheck(AppUser user, String orgId) {
        if (orgId != null) {
            Org org = orgRepo.findById(orgId).orElse(null);
            if (org != null && org.getTier() != null) {
                return org.getTier().toUpperCase();
            }
        }
        return user.getTier() != null ? user.getTier().toUpperCase() : "FREE";
    }

    /**
     * Assert that the user's tier allows running a plan with the given complexity profile.
     * Plans where the majority (>50%) of tasks are HIGH complexity are blocked for
     * FREE tier users and users/orgs without BYOK (own API keys).
     *
     * <p>SUPER_ADMIN, PRO, and ENTERPRISE (with BYOK) users are always allowed.
     *
     * @throws ResponseStatusException 403 if the plan complexity exceeds the user's tier allowance
     */
    public void assertComplexityAllowed(String userId, TaskPlan plan) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        // SUPER_ADMIN always allowed
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        // Check if plan is high-complexity
        if (!isHighComplexityPlan(plan)) return;

        // Resolve effective tier and BYOK status
        String effectiveTier;
        boolean hasByok;
        if (user.getOrgId() != null) {
            Org org = orgRepo.findById(user.getOrgId()).orElse(null);
            effectiveTier = (org != null && org.getTier() != null) ? org.getTier().toUpperCase() : "FREE";
            hasByok = org != null && org.isApiKeysProvided();
        } else {
            effectiveTier = user.getTier() != null ? user.getTier().toUpperCase() : "FREE";
            hasByok = false; // standalone users don't have org-level BYOK
        }

        // V141 — TEAM (renamed from MAX), PRO and ENTERPRISE with BYOK are always allowed
        if ("TEAM".equals(effectiveTier)) return;
        if ("ENTERPRISE".equals(effectiveTier) && hasByok) return;
        if ("PRO".equals(effectiveTier)) return;

        // FREE tier or ENTERPRISE without BYOK — block high-complexity plans
        log.info("[TierEnforcement] Blocking high-complexity plan for user {} (tier={}, byok={})",
                userId, effectiveTier, hasByok);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This plan has high overall complexity and requires a paid tier (Pro, Team) or BYOK (Bring Your Own Keys) setup. " +
                "Upgrade your plan or configure your own API keys to run complex plans.");
    }

    /**
     * Returns true if the majority (>50%) of tasks in the plan are HIGH complexity.
     */
    private boolean isHighComplexityPlan(TaskPlan plan) {
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) return false;

        List<TaskDefinition> tasks = plan.getTasks();
        long highCount = tasks.stream()
                .filter(t -> "HIGH".equalsIgnoreCase(t.getComplexity()))
                .count();

        return highCount > tasks.size() / 2.0;
    }

    /** Resolves the max_apply_suggestions limit for the user's effective tier. */
    private int getApplySuggestionLimit(AppUser user) {
        String tier;
        if (user.getOrgId() != null) {
            Org org = orgRepo.findById(user.getOrgId()).orElse(null);
            tier = (org != null && org.getTier() != null) ? org.getTier() : "FREE";
        } else {
            tier = user.getTier() != null ? user.getTier() : "FREE";
        }
        return tierConfigRepo.findById(tier.toUpperCase())
                .map(TierConfig::getMaxApplySuggestions)
                .orElse(2); // fallback FREE
    }

    /** Increment run counter after a run is accepted. Consumes bonus run if over quota. */
    public void incrementRunCount(String userId) {
        AppUser user = userRepo.findById(userId).orElse(null);
        if (user == null) return;

        if (user.getOrgId() != null) {
            orgRepo.findById(user.getOrgId()).ifPresent(org -> {
                maybeResetOrgCounter(org);
                org.setMonthlyRunCount(org.getMonthlyRunCount() + 1);
                orgRepo.save(org);

                // If now over limit, consume a bonus run
                int limit = effectiveOrgLimit(org);
                if (limit >= 0 && org.getMonthlyRunCount() > limit) {
                    runTopupService.consumeBonusRunForOrg(org.getId());
                }
            });
        } else {
            maybeResetUserCounter(user);
            user.setPersonalRunCount(user.getPersonalRunCount() + 1);
            userRepo.save(user);

            // If now over limit, consume a bonus run
            int limit = getMonthlyLimit(user.getTier());
            if (limit >= 0 && user.getPersonalRunCount() > limit) {
                runTopupService.consumeBonusRun(userId);
            }
        }
    }

    /**
     * Decrement run counter when a run fails completely (refund quota).
     * Only decrements if counter is > 0 to avoid negative counts.
     */
    public void decrementRunCount(String userId) {
        decrementRunCount(userId, 1);
    }

    /**
     * Decrement run counter by N credits when a run fails completely (refund quota).
     * Clamps at zero to avoid negative counts.
     */
    public void decrementRunCount(String userId, int credits) {
        if (credits <= 0) return;
        AppUser user = userRepo.findById(userId).orElse(null);
        if (user == null) return;

        if (user.getOrgId() != null) {
            orgRepo.findById(user.getOrgId()).ifPresent(org -> {
                int current = org.getMonthlyRunCount();
                int refund = Math.min(credits, current);
                if (refund > 0) {
                    org.setMonthlyRunCount(current - refund);
                    orgRepo.save(org);
                    log.info("Refunded {} credit(s) for org {} (new count: {})", refund, org.getId(), org.getMonthlyRunCount());
                }
            });
        } else {
            int current = user.getPersonalRunCount();
            int refund = Math.min(credits, current);
            if (refund > 0) {
                user.setPersonalRunCount(current - refund);
                userRepo.save(user);
                log.info("Refunded {} credit(s) for user {} (new count: {})", refund, userId, user.getPersonalRunCount());
            }
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

        // V141 — TEAM (renamed from MAX) and Enterprise with own keys = unlimited.
        // The v2.6.1-Wave-1 per-seat TEAM tier was deleted in V141.
        if ("TEAM".equals(effectiveTier)) return -1;
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
        String tier = user.getTier() != null ? user.getTier().toUpperCase() : "FREE";
        int limit = getMonthlyLimit(tier);
        if (limit < 0) return; // unlimited

        // FREE tier: lifetime limit — never reset
        if ("FREE".equals(tier)) {
            if (user.getPersonalRunCount() >= limit) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "You've used all " + limit + " free runs. Upgrade to Pro for 20 credits/month or Team for unlimited BYOK.");
            }
            return;
        }

        maybeResetUserCounter(user);
        if (user.getPersonalRunCount() >= limit) {
            // Check bonus runs before rejecting
            long bonus = runTopupService.getAvailableBonus(user.getId());
            if (bonus > 0) {
                log.debug("User {} over quota but has {} bonus runs available", user.getId(), bonus);
                return; // allow — bonus will be consumed in incrementRunCount
            }
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Monthly run quota exceeded (" + limit + " runs). Upgrade your plan or buy extra runs.");
        }
    }

    private void assertOrgQuota(Org org) {
        int limit = effectiveOrgLimit(org);
        if (limit < 0) return; // unlimited
        maybeResetOrgCounter(org);
        if (org.getMonthlyRunCount() >= limit) {
            // Check org + member bonus runs before rejecting
            long bonus = runTopupService.getAvailableBonusForOrg(org.getId());
            if (bonus > 0) {
                log.debug("Org {} over quota but has {} bonus runs available", org.getId(), bonus);
                return; // allow — bonus will be consumed in incrementRunCount
            }
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Org monthly run quota exceeded (" + limit + " runs). Upgrade your plan or buy extra runs.");
        }
    }

    private int getMonthlyLimit(String tier) {
        if (tier == null) return FALLBACK_FREE;
        return tierConfigRepo.findById(tier.toUpperCase())
                .map(TierConfig::getMonthlyRunLimit)
                .orElseGet(() -> switch (tier.toUpperCase()) {
                    case "ENTERPRISE" -> FALLBACK_ENT;
                    case "TEAM"       -> FALLBACK_TEAM;  // V141 — renamed from MAX
                    case "PRO"        -> FALLBACK_PRO;
                    default           -> FALLBACK_FREE;
                });
    }

    private void maybeResetUserCounter(AppUser user) {
        // FREE tier uses lifetime limit — never reset
        String tier = user.getTier() != null ? user.getTier().toUpperCase() : "FREE";
        if ("FREE".equals(tier)) return;

        if (shouldResetUsage(user.getId(), user.getRunCountResetAt())) {
            user.setPersonalRunCount(0);
            user.setRunCountResetAt(Instant.now());
            userRepo.save(user);
            log.debug("[TierEnforcement] Reset run counter for user {}", user.getId());
        }
    }

    private void maybeResetOrgCounter(Org org) {
        if (shouldResetOrgUsage(org, org.getRunCountResetAt())) {
            org.setMonthlyRunCount(0);
            org.setRunCountResetAt(Instant.now());
            orgRepo.save(org);
            log.debug("[TierEnforcement] Reset run counter for org {}", org.getId());
        }
    }

    /**
     * v2.5.6 — exposed for {@link MonthlyRunCounterResetJob} so the cron path
     * can reuse the same predicate as the lazy-submit path. Returns true when
     * the user's counter should be zeroed.
     */
    boolean shouldResetUserCounterForJob(AppUser user) {
        return shouldResetUsage(user.getId(), user.getRunCountResetAt());
    }

    /** v2.5.6 — cron counterpart of {@link #maybeResetOrgCounter}. */
    boolean shouldResetOrgCounterForJob(Org org) {
        return shouldResetOrgUsage(org, org.getRunCountResetAt());
    }

    /**
     * Billing-cycle-aware usage reset for individual users.
     * If user has an active subscription, resets when current billing period starts.
     * Falls back to calendar-month reset for FREE users (no subscription).
     */
    private boolean shouldResetUsage(String userId, Instant lastReset) {
        return subscriptionRepo.findActiveByUserId(userId)
                .map(sub -> shouldResetForSubscription(sub, lastReset))
                .orElse(isNewCalendarMonth(lastReset));
    }

    /**
     * Billing-cycle-aware usage reset for organizations.
     * Looks up subscription by org members or org-level subscription.
     */
    private boolean shouldResetOrgUsage(Org org, Instant lastReset) {
        // Org subscriptions are stored with the org admin's userId
        return subscriptionRepo.findActiveByUserId(org.getCreatedByUserId())
                .map(sub -> shouldResetForSubscription(sub, lastReset))
                .orElse(isNewCalendarMonth(lastReset));
    }

    /**
     * Reset if the subscription's current period started AFTER the last reset.
     * This aligns usage resets with billing cycles rather than calendar months.
     */
    private boolean shouldResetForSubscription(BillingSubscription sub, Instant lastReset) {
        if (lastReset == null) return true;
        Instant periodStart = sub.getCurrentPeriodStart();
        if (periodStart == null) return isNewCalendarMonth(lastReset);
        // Reset when a new billing period has started since our last reset
        return periodStart.isAfter(lastReset);
    }

    /**
     * v2.5.6 — billing zone for the calendar-month boundary check. Our contract
     * resets quotas at 00:00 IST on the 1st of each month, so the month index
     * comparison must happen in Asia/Kolkata — not UTC (which fires at 05:30 IST
     * on the 1st, a ~5.5h off-by-one that showed on month-boundary days).
     */
    static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

    /** Calendar-month boundary check for non-subscribed users, evaluated in IST. */
    private boolean isNewCalendarMonth(Instant lastReset) {
        if (lastReset == null) return true;
        ZonedDateTime resetTime = lastReset.atZone(BILLING_ZONE);
        ZonedDateTime now = Instant.now().atZone(BILLING_ZONE);
        return now.getYear() > resetTime.getYear() ||
               now.getMonthValue() > resetTime.getMonthValue();
    }

    // ── Tier resolution for async contexts ─────────────────────────────────

    /**
     * Resolves the effective subscription tier for a user (org tier takes precedence).
     * Safe to call from async pipeline threads where there is no auth principal.
     */
    public String resolveUserTier(String userId) {
        if (userId == null) return "FREE";
        AppUser user = userRepo.findById(userId).orElse(null);
        if (user == null) return "FREE";
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return "ENTERPRISE";
        if (user.getOrgId() != null) {
            Org org = orgRepo.findById(user.getOrgId()).orElse(null);
            return (org != null && org.getTier() != null) ? org.getTier().toUpperCase() : "FREE";
        }
        return user.getTier() != null ? user.getTier().toUpperCase() : "FREE";
    }

    /**
     * Resolves the per-run USD cost budget for the given tier and BYOK status.
     * Returns -1 for unlimited (ENTERPRISE, PRO with BYOK, SUPER_ADMIN).
     */
    public double resolveRunCostBudget(String tier, boolean hasByok) {
        final String effectiveTier = (tier != null) ? tier.toUpperCase() : "FREE";

        // V141 — TEAM (renamed from MAX), ENTERPRISE, and PRO+BYOK users have no per-run cost limit
        if ("TEAM".equals(effectiveTier)) return -1;
        if ("ENTERPRISE".equals(effectiveTier)) return -1;
        if ("PRO".equals(effectiveTier) && hasByok) return -1;

        // Look up from tier_config table, fall back to constants
        return tierConfigRepo.findById(effectiveTier)
                .map(TierConfig::getMaxRunCostUsd)
                .orElseGet(() -> switch (effectiveTier) {
                    case "PRO" -> DEFAULT_PRO_COST_BUDGET;
                    default    -> DEFAULT_FREE_COST_BUDGET;
                });
    }
}
