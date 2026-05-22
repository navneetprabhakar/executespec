package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * v2.5.6 — cron-driven monthly run counter reset.
 *
 * <p>The lazy-reset path in {@link TierEnforcementService} only fires when a user submits a run.
 * Dashboards and quota checks for dormant users kept showing last-month's consumption well into the
 * new month, especially in the first few hours of the 1st (IST) when nobody had hit submit yet.
 *
 * <p>This job runs at {@code 0 0 0 1 * *} (midnight on the 1st) in {@code Asia/Kolkata}. Hitting
 * all per-user quotas via {@code personalRunCount} and every org via {@code monthlyRunCount},
 * calling the same {@link TierEnforcementService} predicates so subscribed users still honour their
 * billing-cycle anchor rather than being blindly zeroed.
 *
 * <p>2026-05-22 — FREE users are now reset too. FREE was a lifetime quota; it is now a monthly "15
 * runs/month" cap that resets on the calendar-month boundary, matching {@code
 * TierEnforcementService .maybeResetUserCounter}.
 *
 * <p>{@link SchedulerLock} prevents double-firing when multiple app instances hit the cron moment
 * together (MIG / Cloud Run).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonthlyRunCounterResetJob {

  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;
  private final TierEnforcementService tierEnforcement;
  private final BillingSubscriptionRepository subscriptionRepo;

  /**
   * Fires at 00:00 IST on the 1st of every month. Iterates users + orgs and delegates to {@link
   * TierEnforcementService}'s existing reset predicates so subscribed users still anchor to their
   * billing period; FREE users reset on the calendar-month boundary. Idempotent — subsequent
   * firings in the same month are no-ops.
   */
  @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "monthlyRunCounterReset", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
  @Transactional
  public void resetMonthlyCounters() {
    Instant startedAt = Instant.now();
    // B-08 (audit, 2026-05-09) — pre-load every active subscription once.
    // The legacy implementation called `subscriptionRepo.findActiveByUserId`
    // for each app_user + each org, producing 2× N round-trips at midnight
    // IST that exceeded the lock window on 50k+ user DBs. One bulk SELECT
    // here, in-memory match per row.
    List<BillingSubscription> activeSubs = subscriptionRepo.findAllActiveOrPastDue();
    Map<String, BillingSubscription> activeByUser =
        activeSubs.stream()
            .collect(
                Collectors.toMap(
                    BillingSubscription::getUserId,
                    Function.identity(),
                    (a, b) -> a)); // tolerate duplicates by keeping the first seen

    Instant resetStamp = Instant.now();

    // ── Users ─────────────────────────────────────────────────────────────
    long userScanned = 0;
    List<AppUser> userBatch = new ArrayList<>();
    for (AppUser user : userRepo.findAll()) {
      userScanned++;
      // 2026-05-22 — FREE now resets monthly too (was lifetime), matching the "15 runs/month" copy.
      if (tierEnforcement.shouldResetUserCounterForJob(user, activeByUser)) {
        user.setPersonalRunCount(0);
        user.setRunCountResetAt(resetStamp);
        userBatch.add(user);
      }
    }
    if (!userBatch.isEmpty()) userRepo.saveAll(userBatch);

    // ── Orgs ──────────────────────────────────────────────────────────────
    long orgScanned = 0;
    List<Org> orgBatch = new ArrayList<>();
    for (Org org : orgRepo.findAll()) {
      orgScanned++;
      if (tierEnforcement.shouldResetOrgCounterForJob(org, activeByUser)) {
        org.setMonthlyRunCount(0);
        org.setRunCountResetAt(resetStamp);
        orgBatch.add(org);
      }
    }
    if (!orgBatch.isEmpty()) orgRepo.saveAll(orgBatch);

    log.info(
        "[MonthlyRunCounterReset] users scanned={} reset={} · orgs scanned={} reset={} · "
            + "active-subs prefetched={} · took {}ms",
        userScanned,
        userBatch.size(),
        orgScanned,
        orgBatch.size(),
        activeByUser.size(),
        java.time.Duration.between(startedAt, Instant.now()).toMillis());
  }
}
