package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;

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
 * ENTERPRISE+PRO users via {@code personalRunCount} and every org via {@code monthlyRunCount},
 * calling the same {@link TierEnforcementService} predicates so subscribed users still honour their
 * billing-cycle anchor rather than being blindly zeroed.
 *
 * <p>FREE users are intentionally skipped — per {@code TierEnforcementService
 * .maybeResetUserCounter}, FREE is a lifetime quota, not monthly.
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

  /**
   * Fires at 00:00 IST on the 1st of every month. Iterates users + orgs and delegates to {@link
   * TierEnforcementService}'s existing reset predicates so subscribed users still anchor to their
   * billing period and FREE users stay skipped. Idempotent — subsequent firings in the same month
   * are no-ops.
   */
  @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "monthlyRunCounterReset", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
  @Transactional
  public void resetMonthlyCounters() {
    Instant startedAt = Instant.now();
    Instant now = Instant.now();

    // B-08 fix (2026-05-09) — was N+1: per-user save() opened N transactions and
    // triggered N flushes, taking minutes at 10K+ users and blocking concurrent
    // quota checks reading mid-job state. Now: scan once in-memory to collect IDs
    // approved by the per-tier predicate, then fire a single bulk UPDATE per
    // table. Predicate logic preserved (subscribed users still anchor to their
    // billing-cycle period).
    List<String> userIdsToReset = new ArrayList<>();
    long userScanned = 0;
    for (AppUser user : userRepo.findAll()) {
      userScanned++;
      String tier = user.getTier() != null ? user.getTier().toUpperCase() : "FREE";
      if ("FREE".equals(tier)) continue;
      if (tierEnforcement.shouldResetUserCounterForJob(user)) {
        userIdsToReset.add(user.getId());
      }
    }
    int userReset = 0;
    if (!userIdsToReset.isEmpty()) {
      userReset = userRepo.bulkResetPersonalRunCount(userIdsToReset, now);
    }

    List<String> orgIdsToReset = new ArrayList<>();
    long orgScanned = 0;
    for (Org org : orgRepo.findAll()) {
      orgScanned++;
      if (tierEnforcement.shouldResetOrgCounterForJob(org)) {
        orgIdsToReset.add(org.getId());
      }
    }
    int orgReset = 0;
    if (!orgIdsToReset.isEmpty()) {
      orgReset = orgRepo.bulkResetMonthlyRunCount(orgIdsToReset, now);
    }

    log.info(
        "[MonthlyRunCounterReset] users scanned={} reset={} · orgs scanned={} reset={} · took {}ms",
        userScanned,
        userReset,
        orgScanned,
        orgReset,
        java.time.Duration.between(startedAt, Instant.now()).toMillis());
  }
}
