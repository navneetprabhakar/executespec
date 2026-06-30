package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiUsagePeriodRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class ManagedAiUsagePeriodService {

  private final ManagedAiUsagePeriodRepository periodRepository;
  private final ManagedAiTierResolver tierResolver;
  private final PipelineRunRepository pipelineRunRepository;
  private final ManagedAiNotificationService notificationService;

  ManagedAiUsagePeriod currentOrCreate(
      ManagedAiUserContext user,
      ManagedAiProperties.TierPolicy policy,
      Instant now,
      boolean locked) {
    Optional<ManagedAiUsagePeriod> existing =
        locked
            ? periodRepository.findCurrentForUpdate(user.userId(), now)
            : periodRepository.findCurrent(user.userId(), now);
    if (existing.isPresent()) {
      ManagedAiUsagePeriod period = existing.get();
      // Run-count retirement (2026-06-22): policy-derived caps are snapshotted onto the period at
      // creation. Refresh them from the CURRENT policy so a tier policy change (e.g. the new FREE
      // grant) reaches users mid-period without resetting their usage counters.
      if (refreshPolicyCaps(period, policy)) {
        period.setUpdatedAt(now);
        periodRepository.save(period);
      }
      return period;
    }
    return periodRepository.save(newPeriod(user, policy, now));
  }

  /**
   * Re-apply the current tier policy's caps onto an existing period. Returns true if anything
   * changed. Usage fields ({@code *UsedUsd}, {@code aiCreditsUsed}) are intentionally preserved.
   */
  private static boolean refreshPolicyCaps(
      ManagedAiUsagePeriod p, ManagedAiProperties.TierPolicy policy) {
    boolean changed = false;
    if (p.getIncludedAiCredits() != policy.getIncludedCredits()) {
      p.setIncludedAiCredits(policy.getIncludedCredits());
      changed = true;
    }
    if (p.getRawCogsCapUsd() != policy.getRawCogsCapUsd()) {
      p.setRawCogsCapUsd(policy.getRawCogsCapUsd());
      changed = true;
    }
    if (p.getFrontierRawCapUsd() != policy.getFrontierRawCapUsd()) {
      p.setFrontierRawCapUsd(policy.getFrontierRawCapUsd());
      changed = true;
    }
    if (p.getValueModeAfterRawUsd() != policy.getValueModeAfterRawUsd()) {
      p.setValueModeAfterRawUsd(policy.getValueModeAfterRawUsd());
      changed = true;
    }
    if (p.getCheapOnlyAfterRawUsd() != policy.getCheapOnlyAfterRawUsd()) {
      p.setCheapOnlyAfterRawUsd(policy.getCheapOnlyAfterRawUsd());
      changed = true;
    }
    if (p.getHardStopAfterRawUsd() != policy.effectiveHardStopUsd()) {
      p.setHardStopAfterRawUsd(policy.effectiveHardStopUsd());
      changed = true;
    }
    return changed;
  }

  Optional<ManagedAiUsagePeriod> findCurrent(String userId, Instant now) {
    return periodRepository.findCurrent(userId, now);
  }

  ManagedAiUserContext resolveUser(String runId, RunKeyContext keys) {
    if (keys != null && keys.userId() != null && !keys.userId().isBlank()) {
      return new ManagedAiUserContext(
          keys.userId(), blankToNull(keys.orgId()), tierResolver.resolveUserTier(keys.userId()));
    }
    PipelineRun run = runId == null ? null : pipelineRunRepository.findById(runId).orElse(null);
    if (run == null || run.getCreatedByUserId() == null || run.getCreatedByUserId().isBlank()) {
      return null;
    }
    String tier =
        run.getUserTier() == null || run.getUserTier().isBlank()
            ? tierResolver.resolveUserTier(run.getCreatedByUserId())
            : run.getUserTier().trim().toUpperCase(Locale.ROOT);
    return new ManagedAiUserContext(run.getCreatedByUserId(), blankToNull(run.getOrgId()), tier);
  }

  void syncState(ManagedAiUsagePeriod period, String state) {
    if (state == null || state.equals(period.getRoutingState())) return;
    String before = period.getRoutingState();
    period.setRoutingState(state);
    period.setUpdatedAt(Instant.now());
    periodRepository.save(period);
    notificationService.notifyTransition(period, before, state);
  }

  void saveWithTransition(ManagedAiUsagePeriod period, String before, String after) {
    period.setRoutingState(after);
    period.setUpdatedAt(Instant.now());
    periodRepository.save(period);
    if (after != null && !after.equals(before)) {
      notificationService.notifyTransition(period, before, after);
    }
  }

  private static ManagedAiUsagePeriod newPeriod(
      ManagedAiUserContext user, ManagedAiProperties.TierPolicy policy, Instant now) {
    LocalDate first = LocalDate.ofInstant(now, ZoneOffset.UTC).withDayOfMonth(1);
    Instant start = first.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant end = first.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    return ManagedAiUsagePeriod.builder()
        .id(UUID.randomUUID().toString())
        .userId(user.userId())
        .orgId(user.orgId())
        .tier(user.tier())
        .periodStart(start)
        .periodEnd(end)
        .includedAiCredits(policy.getIncludedCredits())
        .purchasedAiCredits(0)
        .aiCreditsUsed(0)
        .rawCogsCapUsd(policy.getRawCogsCapUsd())
        .rawCogsUsedUsd(0.0)
        .frontierRawCapUsd(policy.getFrontierRawCapUsd())
        .frontierRawUsedUsd(0.0)
        .valueModeAfterRawUsd(policy.getValueModeAfterRawUsd())
        .cheapOnlyAfterRawUsd(policy.getCheapOnlyAfterRawUsd())
        .hardStopAfterRawUsd(policy.effectiveHardStopUsd())
        .routingState(ManagedAiRoutingPolicy.FULL)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  ManagedAiUsagePeriod grantPurchasedCredits(
      ManagedAiUserContext user, ManagedAiProperties.TierPolicy policy, long credits, Instant now) {
    if (credits <= 0) {
      throw new IllegalArgumentException("credits must be positive");
    }
    ManagedAiUsagePeriod period = currentOrCreate(user, policy, now, true);
    period.setPurchasedAiCredits(period.getPurchasedAiCredits() + credits);
    period.setUpdatedAt(now);
    return periodRepository.save(period);
  }
}
