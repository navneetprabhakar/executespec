package com.navneet.executespec.credit;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-161 — v3 Free-tier lifetime run credit service.
 *
 * <p>The v3 Free tier replaces the v2 "5 runs per month forever" with "5 lifetime runs + 5 per
 * friend invited (cap 25)". This service is the single arbiter of that balance.
 *
 * <p>Cohabitation: v2 run-submission keeps using {@link
 * com.navneet.executespec.auth.service.TierEnforcementService} + monthly counters; v3 surfaces (the
 * entitlements API and the v3 composer) call into this service. The two paths are independent — a
 * v2 run does NOT decrement the lifetime balance and a v3 run does NOT touch personal_run_count.
 *
 * <p>Paid tiers (PRO / TEAM / ENTERPRISE) are unlimited; this service no-ops for them so the same
 * wiring is safe for any user.
 *
 * <p>Defaults applied per D-002: - Baseline grant : 5 lifetime runs (set by V152 default) -
 * Per-invite grant : 5 runs (R-162 calls grantInviteCredit) - Cap : 25 lifetime runs (= 5 base + 4
 * invites × 5)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifetimeCreditService {

  /** Per-invite grant — see greenfield doc §7.5. */
  public static final int INVITE_GRANT = 5;

  private final AppUserRepository userRepo;

  /**
   * Returns the user's current Free-tier lifetime balance, or {@link Integer#MAX_VALUE} for paid
   * tiers (treated as unlimited).
   */
  @Transactional(readOnly = true)
  public int currentBalance(String userId) {
    Optional<AppUser> u = userRepo.findById(userId);
    if (u.isEmpty()) return 0;
    AppUser user = u.get();
    if (isPaidTier(user)) return Integer.MAX_VALUE;
    return user.getLifetimeRunCreditBalance();
  }

  /** True iff the user can submit a Free-tier run right now. */
  @Transactional(readOnly = true)
  public boolean canSubmit(String userId) {
    return currentBalance(userId) > 0;
  }

  /**
   * Decrement on Free-tier run start. No-op for paid tiers.
   *
   * <p>Returns the post-decrement balance, or {@link Integer#MAX_VALUE} if the user is on a paid
   * tier and the decrement was skipped.
   *
   * <p>Throws {@link InsufficientCreditException} when the user is on Free tier and the balance is
   * already 0 — callers should display the upgrade-or-invite nudge (R-163).
   */
  @Transactional
  public int decrementForRun(String userId) {
    AppUser user = loadOrThrow(userId);
    if (isPaidTier(user)) return Integer.MAX_VALUE;
    int balance = user.getLifetimeRunCreditBalance();
    if (balance <= 0) {
      throw new InsufficientCreditException(userId);
    }
    user.setLifetimeRunCreditBalance(balance - 1);
    userRepo.save(user);
    log.info("v3 lifetime credit decremented for {} → balance={}", userId, balance - 1);
    return balance - 1;
  }

  /**
   * Refund a previously-decremented credit (e.g. when the run fails before any LLM cost was
   * incurred). Idempotent — caps at the user's lifetime cap so over-refunding can't push the
   * balance past the cap.
   */
  @Transactional
  public int refund(String userId) {
    AppUser user = loadOrThrow(userId);
    if (isPaidTier(user)) return Integer.MAX_VALUE;
    int newBalance =
        Math.min(user.getLifetimeRunCreditBalance() + 1, user.getLifetimeRunCreditCap());
    user.setLifetimeRunCreditBalance(newBalance);
    userRepo.save(user);
    return newBalance;
  }

  /**
   * Grant the per-invite bonus. Called by the referral mechanism (R-162) on the referee's first
   * successful run (D-003 default trigger). Capped at the user's lifetime cap.
   *
   * <p>Returns the post-grant balance.
   */
  @Transactional
  public int grantInviteCredit(String userId) {
    AppUser user = loadOrThrow(userId);
    if (isPaidTier(user)) return Integer.MAX_VALUE;
    int newBalance =
        Math.min(user.getLifetimeRunCreditBalance() + INVITE_GRANT, user.getLifetimeRunCreditCap());
    user.setLifetimeRunCreditBalance(newBalance);
    userRepo.save(user);
    log.info(
        "v3 lifetime credit granted +{} for {} → balance={}", INVITE_GRANT, userId, newBalance);
    return newBalance;
  }

  private AppUser loadOrThrow(String userId) {
    return userRepo
        .findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
  }

  /**
   * The v3 ladder is Free / Pro / Team / Enterprise. Any tier other than FREE is treated as
   * unlimited for credit-decrement purposes. MAX (legacy from V141 path) is treated as Team
   * (unlimited) per the v3 terminology mapping.
   */
  public static boolean isPaidTier(AppUser user) {
    if (user == null || user.getTier() == null) return false;
    String t = user.getTier();
    return !"FREE".equalsIgnoreCase(t);
  }

  public static class InsufficientCreditException extends RuntimeException {
    public InsufficientCreditException(String userId) {
      super("user " + userId + " has 0 lifetime credits remaining");
    }
  }
}
