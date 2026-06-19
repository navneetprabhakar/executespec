package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.service.RunTopupService;
import com.navneet.executespec.harness.event.QuotaEvent;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enforces monthly run quotas.
 *
 * <p>Limit resolution order: 1. ENTERPRISE org with api_keys_provided = true → unlimited (-1) 2.
 * Org with monthly_run_limit_override set → use override 3. Org tier default from tier_config table
 * → use tier default 4. Free user personal quota from tier_config → use 'FREE' row
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
  private final ProjectMemberRepository projectMemberRepo;

  /** Quota utilisation at which we emit a one-shot warning notification. */
  private static final double QUOTA_WARNING_THRESHOLD = 0.80;

  /** Safe fallback limits if tier_config rows are missing. */
  /** Fallback only. Canonical FREE quota lives in tier_config and was bumped to 15 by V200. */
  private static final int FALLBACK_FREE = 15;

  /** Fallback only. Canonical PRO quota lives in tier_config and was bumped to 30 by V176. */
  private static final int FALLBACK_PRO = 30;

  /**
   * V165 — TEAM consolidated at $39/seat with BYOK-or-managed keys; runs unlimited against the
   * customer's provider quota.
   */
  private static final int FALLBACK_TEAM = -1;

  /** V140 — ENTERPRISE is unlimited per the website ("Unlimited (fair use)"). */
  private static final int FALLBACK_ENT = -1;

  /** Default per-run cost budgets (USD). */
  private static final double DEFAULT_FREE_COST_BUDGET = 3.0;

  private static final double DEFAULT_PRO_COST_BUDGET = 5.0;

  private static final Map<String, Integer> TIER_RANK =
      Map.of(
          "FREE", 0,
          "PRO", 1,
          "TEAM", 2,
          "MAX", 2,
          "ENTERPRISE", 3);

  /**
   * Atomically checks quota AND increments the run count in a single transaction with pessimistic
   * locking. Prevents TOCTOU race where two concurrent submissions both pass the quota check before
   * either increments. Throws 429/402 if quota exceeded.
   */
  @Transactional
  public void assertAndIncrementRunCount(String userId) {
    AppUser user =
        userRepo
            .findByIdForUpdate(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    if (user.getOrgId() != null) {
      Org org =
          orgRepo
              .findByIdForUpdate(user.getOrgId())
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.INTERNAL_SERVER_ERROR, "Org not found"));
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
    int limit = getMonthlyLimit(resolveEffectiveTier(user));
    if (limit >= 0 && user.getPersonalRunCount() > limit) {
      runTopupService.consumeBonusRun(userId);
    }
    maybeFireQuotaWarning(userId, null, personalBefore, user.getPersonalRunCount(), limit);
  }

  /**
   * Atomically check quota and increment by N credits. Used at plan approval when cost estimation
   * determines multi-credit runs.
   */
  @Transactional
  public void assertAndIncrementRunCount(String userId, int credits) {
    if (credits <= 0) credits = 1;
    AppUser user =
        userRepo
            .findByIdForUpdate(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    if (user.getOrgId() != null) {
      Org org =
          orgRepo
              .findByIdForUpdate(user.getOrgId())
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.INTERNAL_SERVER_ERROR, "Org not found"));
      assertOrgQuota(org);
      maybeResetOrgCounter(org);
      int orgBefore = org.getMonthlyRunCount();
      org.setMonthlyRunCount(orgBefore + credits);
      orgRepo.save(org);
      int limit = effectiveOrgLimit(org);
      if (limit >= 0 && org.getMonthlyRunCount() > limit) {
        for (int i = 0; i < credits; i++) {
          try {
            runTopupService.consumeBonusRunForOrg(org.getId());
          } catch (Exception e) {
            break;
          }
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
    int limit = getMonthlyLimit(resolveEffectiveTier(user));
    if (limit >= 0 && user.getPersonalRunCount() > limit) {
      for (int i = 0; i < credits; i++) {
        try {
          runTopupService.consumeBonusRun(userId);
        } catch (Exception e) {
          break;
        }
      }
    }
    maybeFireQuotaWarning(userId, null, personalBefore, user.getPersonalRunCount(), limit);
  }

  /**
   * Emit a one-shot {@link QuotaEvent.QuotaWarning} when a counter crosses {@link
   * #QUOTA_WARNING_THRESHOLD}. Safe to call with unlimited limits (-1) — no-ops. Runs in the
   * caller's transaction; publish is best-effort and swallows exceptions so it never impacts the
   * increment path.
   */
  private void maybeFireQuotaWarning(
      String userId, String orgId, int before, int after, int limit) {
    if (limit <= 0) return; // unlimited or invalid
    double threshold = limit * QUOTA_WARNING_THRESHOLD;
    if (before >= threshold || after < threshold) return;
    try {
      int percent = (int) Math.floor(100.0 * after / limit);
      eventPublisher.publishEvent(
          new QuotaEvent.QuotaWarning(userId, orgId, "RUNS", after, limit, percent));
    } catch (Exception e) {
      log.warn(
          "Failed to publish QuotaWarning for user={} org={}: {}", userId, orgId, e.getMessage());
    }
  }

  /** Assert the given user can submit another run. Throws 429 if quota exceeded. */
  public void assertCanSubmitRun(String userId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    // SUPER_ADMIN has no limit
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    if (user.getOrgId() != null) {
      Org org =
          orgRepo
              .findById(user.getOrgId())
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.INTERNAL_SERVER_ERROR, "Org not found"));
      assertOrgQuota(org);
    } else {
      assertUserQuota(user);
    }
  }

  /**
   * Assert the given user can apply a suggestion (chat copilot or review) to the given run. Throws
   * 429 if the per-run apply limit for the user's tier is exceeded. SUPER_ADMIN and ENTERPRISE
   * (limit=-1) are always allowed.
   */
  public void assertCanApplySuggestion(String userId, PipelineRun run) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    // SUPER_ADMIN has no limit
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    int limit = getApplySuggestionLimit(user);
    if (limit < 0) return; // unlimited (ENTERPRISE)

    if (run.getApplySuggestionCount() >= limit) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
          "Apply limit reached ("
              + run.getApplySuggestionCount()
              + "/"
              + limit
              + "). Create a fresh run instead.");
    }
  }

  /**
   * V140 — Assert the caller can create another project under {@code orgId}.
   *
   * <p>Resolution order: SUPER_ADMIN bypass; otherwise look up the effective tier (org tier if
   * {@code orgId} is non-null, else user.tier) and read {@code tier_config.max_projects_per_org}.
   * If the limit is non-negative and the org's current ACTIVE project count meets or exceeds it,
   * throws 403 with an upgrade hint.
   *
   * <p>Throws 401 if the user is unknown.
   */
  public void assertCanCreateProject(String userId, String orgId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    String tier = resolveTierForLimitCheck(user, orgId);
    int limit = tierConfigRepo.findById(tier).map(TierConfig::getMaxProjectsPerOrg).orElse(-1);
    if (limit < 0) return; // unlimited

    // Project.org_id is non-nullable in the schema, so org-level count
    // is the only meaningful scope. If orgId is null we conservatively
    // skip enforcement (the create call will fail at persist anyway).
    if (orgId == null) return;
    // V191 (PR-B-part-2) — count only undeleted ACTIVE projects against the tier cap. A row
    // that's still in the 30-day restore window is invisible to the user and should not be
    // double-charged against their project quota. Soft-deleted projects are reaped by the
    // cleanup sweeper (PR-H); until then we treat them as gone for cap-enforcement purposes.
    long current = projectRepo.countByOrgIdAndStatusAndDeletedAtIsNull(orgId, "ACTIVE");

    if (current >= limit) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Tier "
              + tier
              + " allows "
              + limit
              + " project"
              + (limit == 1 ? "" : "s")
              + " per org. Upgrade to TEAM or ENTERPRISE for higher caps.");
    }
  }

  /**
   * V140 — Assert the caller can link another repo to {@code projectId}.
   *
   * <p>Resolution order: SUPER_ADMIN bypass; otherwise look up the effective tier (project's org
   * tier if the project has an org, else user.tier) and read {@code
   * tier_config.max_repos_per_project}. If the limit is non-negative and the project already has
   * that many (or more) repo links, throws 403 with an upgrade hint.
   *
   * <p>Throws 401 if the user is unknown, 404 if the project is unknown.
   */
  public void assertCanLinkRepoToProject(String userId, String projectId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

    String tier = resolveTierForLimitCheck(user, project.getOrgId());
    int limit = tierConfigRepo.findById(tier).map(TierConfig::getMaxReposPerProject).orElse(-1);
    if (limit < 0) return; // unlimited

    long current = projectRepoLinkRepo.countByProjectId(projectId);
    if (current >= limit) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Tier "
              + tier
              + " allows "
              + limit
              + " repo"
              + (limit == 1 ? "" : "s")
              + " per project. Upgrade to TEAM or ENTERPRISE for higher caps.");
    }
  }

  /**
   * Resolves the effective tier used by the new project / repo limit checks. Org tier wins when
   * {@code orgId} is non-null; otherwise the caller's effective user tier applies.
   */
  private String resolveTierForLimitCheck(AppUser user, String orgId) {
    if (orgId != null) {
      Org org = orgRepo.findById(orgId).orElse(null);
      if (org != null && org.getTier() != null) {
        return org.getTier().toUpperCase();
      }
    }
    return resolveEffectiveTier(user);
  }

  /**
   * Assert that the user's tier allows running a plan with the given complexity profile. Plans
   * where the majority (>50%) of tasks are HIGH complexity are blocked for FREE tier users.
   *
   * <p>SUPER_ADMIN and paid tiers are allowed. External Enterprise project membership is treated as
   * a paid tier while that membership exists.
   *
   * @throws ResponseStatusException 403 if the plan complexity exceeds the user's tier allowance
   */
  public void assertComplexityAllowed(String userId, TaskPlan plan) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    // SUPER_ADMIN always allowed
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    // Check if plan is high-complexity
    if (!isHighComplexityPlan(plan)) return;

    // Resolve effective tier and BYOK status. External Enterprise project membership upgrades the
    // feature tier while the membership exists, but BYOK still comes only from the user's own org.
    String effectiveTier = resolveEffectiveTier(user);
    boolean hasByok;
    if (user.getOrgId() != null) {
      Org org = orgRepo.findById(user.getOrgId()).orElse(null);
      hasByok = org != null && org.isApiKeysProvided();
    } else {
      hasByok = false; // standalone users don't have org-level BYOK
    }

    // TEAM, PRO and ENTERPRISE are paid capabilities. BYOK still controls cost-cap behavior, not
    // whether an Enterprise project collaborator can run the work they were invited to.
    if ("ENTERPRISE".equals(effectiveTier)) return;
    if ("TEAM".equals(effectiveTier)) return; // v2.6.0 — TEAM unlocks high-complexity
    if ("PRO".equals(effectiveTier)) return;

    // FREE tier — block high-complexity plans
    log.info(
        "[TierEnforcement] Blocking high-complexity plan for user {} (tier={}, byok={})",
        userId,
        effectiveTier,
        hasByok);
    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN,
        "This plan has high overall complexity and requires a paid tier (Pro, Team) or BYOK (Bring"
            + " Your Own Keys) setup. Upgrade your plan or configure your own API keys to run"
            + " complex plans.");
  }

  /** Returns true if the majority (>50%) of tasks in the plan are HIGH complexity. */
  private boolean isHighComplexityPlan(TaskPlan plan) {
    if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) return false;

    List<TaskDefinition> tasks = plan.getTasks();
    long highCount = tasks.stream().filter(t -> "HIGH".equalsIgnoreCase(t.getComplexity())).count();

    return highCount > tasks.size() / 2.0;
  }

  /** Resolves the max_apply_suggestions limit for the user's effective tier. */
  private int getApplySuggestionLimit(AppUser user) {
    String tier = resolveEffectiveTier(user);
    return tierConfigRepo
        .findById(tier.toUpperCase())
        .map(TierConfig::getMaxApplySuggestions)
        .orElse(2); // fallback FREE
  }

  /** Increment run counter after a run is accepted. Consumes bonus run if over quota. */
  public void incrementRunCount(String userId) {
    AppUser user = userRepo.findById(userId).orElse(null);
    if (user == null) return;

    if (user.getOrgId() != null) {
      orgRepo
          .findById(user.getOrgId())
          .ifPresent(
              org -> {
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
      int limit = getMonthlyLimit(resolveEffectiveTier(user));
      if (limit >= 0 && user.getPersonalRunCount() > limit) {
        runTopupService.consumeBonusRun(userId);
      }
    }
  }

  /**
   * Decrement run counter when a run fails completely (refund quota). Only decrements if counter is
   * > 0 to avoid negative counts.
   */
  public void decrementRunCount(String userId) {
    decrementRunCount(userId, 1);
  }

  /**
   * Decrement run counter by N credits when a run fails completely (refund quota). Clamps at zero
   * to avoid negative counts.
   */
  public void decrementRunCount(String userId, int credits) {
    if (credits <= 0) return;
    AppUser user = userRepo.findById(userId).orElse(null);
    if (user == null) return;

    if (user.getOrgId() != null) {
      orgRepo
          .findById(user.getOrgId())
          .ifPresent(
              org -> {
                int current = org.getMonthlyRunCount();
                int refund = Math.min(credits, current);
                if (refund > 0) {
                  org.setMonthlyRunCount(current - refund);
                  orgRepo.save(org);
                  log.info(
                      "Refunded {} credit(s) for org {} (new count: {})",
                      refund,
                      org.getId(),
                      org.getMonthlyRunCount());
                }
              });
    } else {
      int current = user.getPersonalRunCount();
      int refund = Math.min(credits, current);
      if (refund > 0) {
        user.setPersonalRunCount(current - refund);
        userRepo.save(user);
        log.info(
            "Refunded {} credit(s) for user {} (new count: {})",
            refund,
            userId,
            user.getPersonalRunCount());
      }
    }
  }

  /**
   * Effective monthly limit for an org: - ENTERPRISE + api_keys_provided → unlimited - override set
   * → override value - else → tier_config default
   */
  public int effectiveOrgLimit(Org org) {
    // Use the org's actual tier. Payment verification gates tier *upgrades*
    // (handled at the billing layer), not run quota enforcement.
    String effectiveTier = org.getTier() != null ? org.getTier() : "FREE";

    // ENTERPRISE with own keys = unlimited (BYOK contract).
    if ("ENTERPRISE".equals(effectiveTier) && org.isApiKeysProvided()) return -1;

    // Per-org override
    if (org.getMonthlyRunLimitOverride() != null) return org.getMonthlyRunLimitOverride();

    // V165 — TEAM consolidated to a single $39/seat plan whose
    // tier_config.monthly_run_limit is set to -1 (unlimited). The
    // legacy per-seat × 50 multiplication is no longer needed; if a
    // future product decision reintroduces a per-seat cap, do it via
    // org.monthly_run_limit_override or a new tier_config column rather
    // than re-introducing tier-specific math here.
    return getMonthlyLimit(effectiveTier);
  }

  /** Effective monthly limit for a solo FREE user. */
  public int effectiveUserLimit(AppUser user) {
    return getMonthlyLimit(resolveEffectiveTier(user));
  }

  private void assertUserQuota(AppUser user) {
    String tier = resolveEffectiveTier(user);
    int limit = getMonthlyLimit(tier);
    if (limit < 0) return; // unlimited

    // 2026-05-22 — FREE is a MONTHLY quota (matches the "15 runs/month" copy + tier_config),
    // resetting each calendar month like the paid tiers (FREE has no subscription →
    // calendar-month).
    maybeResetUserCounter(user);

    // FREE tier keeps a friendlier upgrade message than the generic quota error below.
    if ("FREE".equals(tier)) {
      if (user.getPersonalRunCount() >= limit) {
        long bonus = runTopupService.getAvailableBonus(user.getId());
        if (bonus > 0) {
          log.debug("FREE user {} over quota but has {} bonus runs available", user.getId(), bonus);
          return; // allow — bonus will be consumed in incrementRunCount
        }
        throw new ResponseStatusException(
            HttpStatus.PAYMENT_REQUIRED,
            "You've used all "
                + limit
                + " free runs this month. Upgrade to Pro for 30 credits/month or Team for"
                + " unlimited runs against your own provider keys.");
      }
      return;
    }

    if (user.getPersonalRunCount() >= limit) {
      // Check bonus runs before rejecting
      long bonus = runTopupService.getAvailableBonus(user.getId());
      if (bonus > 0) {
        log.debug("User {} over quota but has {} bonus runs available", user.getId(), bonus);
        return; // allow — bonus will be consumed in incrementRunCount
      }
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
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
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
          "Org monthly run quota exceeded ("
              + limit
              + " runs). Upgrade your plan or buy extra runs.");
    }
  }

  private int getMonthlyLimit(String tier) {
    if (tier == null) return FALLBACK_FREE;
    return tierConfigRepo
        .findById(tier.toUpperCase())
        .map(TierConfig::getMonthlyRunLimit)
        .orElseGet(
            () ->
                switch (tier.toUpperCase()) {
                  case "ENTERPRISE" -> FALLBACK_ENT;
                  case "TEAM" -> FALLBACK_TEAM;
                  case "PRO" -> FALLBACK_PRO;
                  default -> FALLBACK_FREE;
                });
  }

  private void maybeResetUserCounter(AppUser user) {
    // 2026-05-22 — FREE resets monthly too (was lifetime); shouldResetUsage falls back to a
    // calendar-month boundary for users without a subscription.
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
   * v2.5.6 — exposed for {@link MonthlyRunCounterResetJob} so the cron path can reuse the same
   * predicate as the lazy-submit path. Returns true when the user's counter should be zeroed.
   */
  boolean shouldResetUserCounterForJob(AppUser user) {
    return shouldResetUsage(user.getId(), user.getRunCountResetAt());
  }

  /** v2.5.6 — cron counterpart of {@link #maybeResetOrgCounter}. */
  boolean shouldResetOrgCounterForJob(Org org) {
    return shouldResetOrgUsage(org, org.getRunCountResetAt());
  }

  /**
   * B-08 (audit, 2026-05-09) — N+1-free predicate for the monthly cron. Caller pre-loads every
   * active subscription once via {@link
   * com.navneet.executespec.billing.repository.BillingSubscriptionRepository#findAllActiveOrPastDue()},
   * builds a {@code Map<userId, BillingSubscription>}, and passes the per-user lookup in. We skip
   * the {@code subscriptionRepo.findActiveByUserId} round-trip and fall back to the calendar-month
   * boundary when the map has no entry for this user.
   */
  public boolean shouldResetUserCounterForJob(
      AppUser user,
      java.util.Map<String, com.navneet.executespec.billing.entity.BillingSubscription>
          activeByUser) {
    var sub = activeByUser.get(user.getId());
    if (sub != null) {
      return shouldResetForSubscription(sub, user.getRunCountResetAt());
    }
    return isNewCalendarMonth(user.getRunCountResetAt());
  }

  /**
   * B-08 — same shape as {@link #shouldResetUserCounterForJob(AppUser, java.util.Map)} for orgs.
   */
  public boolean shouldResetOrgCounterForJob(
      Org org,
      java.util.Map<String, com.navneet.executespec.billing.entity.BillingSubscription>
          activeByUser) {
    var sub = activeByUser.get(org.getCreatedByUserId());
    if (sub != null) {
      return shouldResetForSubscription(sub, org.getRunCountResetAt());
    }
    return isNewCalendarMonth(org.getRunCountResetAt());
  }

  /**
   * Billing-cycle-aware usage reset for individual users. If user has an active subscription,
   * resets when current billing period starts. Falls back to calendar-month reset for FREE users
   * (no subscription).
   */
  private boolean shouldResetUsage(String userId, Instant lastReset) {
    return subscriptionRepo
        .findActiveByUserId(userId)
        .map(sub -> shouldResetForSubscription(sub, lastReset))
        .orElse(isNewCalendarMonth(lastReset));
  }

  /**
   * Billing-cycle-aware usage reset for organizations. Looks up subscription by org members or
   * org-level subscription.
   */
  private boolean shouldResetOrgUsage(Org org, Instant lastReset) {
    // Org subscriptions are stored with the org admin's userId
    return subscriptionRepo
        .findActiveByUserId(org.getCreatedByUserId())
        .map(sub -> shouldResetForSubscription(sub, lastReset))
        .orElse(isNewCalendarMonth(lastReset));
  }

  /**
   * Reset if the subscription's current period started AFTER the last reset. This aligns usage
   * resets with billing cycles rather than calendar months.
   */
  private boolean shouldResetForSubscription(BillingSubscription sub, Instant lastReset) {
    if (lastReset == null) return true;
    Instant periodStart = sub.getCurrentPeriodStart();
    if (periodStart == null) return isNewCalendarMonth(lastReset);
    // Reset when a new billing period has started since our last reset
    return periodStart.isAfter(lastReset);
  }

  /**
   * v2.5.6 — billing zone for the calendar-month boundary check. Our contract resets quotas at
   * 00:00 IST on the 1st of each month, so the month index comparison must happen in Asia/Kolkata —
   * not UTC (which fires at 05:30 IST on the 1st, a ~5.5h off-by-one that showed on month-boundary
   * days).
   */
  static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

  /** Calendar-month boundary check for non-subscribed users, evaluated in IST. */
  private boolean isNewCalendarMonth(Instant lastReset) {
    if (lastReset == null) return true;
    ZonedDateTime resetTime = lastReset.atZone(BILLING_ZONE);
    ZonedDateTime now = Instant.now().atZone(BILLING_ZONE);
    return now.getYear() > resetTime.getYear() || now.getMonthValue() > resetTime.getMonthValue();
  }

  // Tier resolution for async contexts

  /**
   * Resolves the effective subscription tier for a user (org tier takes precedence). Safe to call
   * from async pipeline threads where there is no auth principal.
   */
  public String resolveUserTier(String userId) {
    if (userId == null) return "FREE";
    AppUser user = userRepo.findById(userId).orElse(null);
    if (user == null) return "FREE";
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return "ENTERPRISE";
    return resolveEffectiveTier(user);
  }

  private String resolveEffectiveTier(AppUser user) {
    String effective = normalizeTier(user.getTier());
    if (user.getOrgId() != null) {
      Org org = orgRepo.findById(user.getOrgId()).orElse(null);
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
    List<String> tiers = projectMemberRepo.findActiveExternalHostOrgTiersByUserId(userId);
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

  /**
   * R-341 — predicate: does this (tier, hasByok) pair need a hard cost cap? Used by the Readymades
   * + Migrations product UIs to decide whether to render the cost-cap input or an info-only "Est.
   * $X on your keys" chip.
   *
   * <p>Backend enforcement of the cap itself is unchanged ({@link #resolveRunCostBudget} returns
   * the dollar value the V2 cost tracker uses to abort runaway runs). This helper just gates the UX
   * surface so TEAM/ENTERPRISE BYOK customers don't see capacity controls they don't pay for.
   *
   * @return {@code true} when FREE or PRO without BYOK; {@code false} for TEAM / ENTERPRISE /
   *     PRO+BYOK / unknown tiers (safe default = no cap UI).
   */
  public boolean requiresCostCap(String tier, boolean hasByok) {
    if (tier == null) return true; // anonymous → treat as FREE-equivalent
    String t = tier.toUpperCase();
    if ("ENTERPRISE".equals(t)) return false;
    if ("TEAM".equals(t)) return false;
    if ("PRO".equals(t)) return !hasByok;
    if ("FREE".equals(t)) return true;
    return false;
  }

  /**
   * Resolves the per-run USD cost budget for the given tier and BYOK status. Returns -1 for
   * unlimited (ENTERPRISE, PRO with BYOK, SUPER_ADMIN).
   */
  public double resolveRunCostBudget(String tier, boolean hasByok) {
    final String effectiveTier = (tier != null) ? tier.toUpperCase() : "FREE";

    // TEAM, ENTERPRISE, and PRO+BYOK users have no per-run cost limit
    if ("TEAM".equals(effectiveTier)) return -1;
    if ("ENTERPRISE".equals(effectiveTier)) return -1;
    if ("PRO".equals(effectiveTier) && hasByok) return -1;

    // Look up from tier_config table, fall back to constants
    return tierConfigRepo
        .findById(effectiveTier)
        .map(TierConfig::getMaxRunCostUsd)
        .orElseGet(
            () ->
                switch (effectiveTier) {
                  case "PRO" -> DEFAULT_PRO_COST_BUDGET;
                  default -> DEFAULT_FREE_COST_BUDGET;
                });
  }
}
