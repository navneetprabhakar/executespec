package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.auth.service.TierEnforcementService;
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
  private final TierEnforcementService tierEnforcementService;
  private final PipelineRunRepository pipelineRunRepository;
  private final ManagedAiNotificationService notificationService;

  ManagedAiUsagePeriod currentOrCreate(
      ManagedAiUserContext user,
      ManagedAiProperties.TierPolicy policy,
      Instant now,
      boolean locked) {
    return (locked
            ? periodRepository.findCurrentForUpdate(user.userId(), now)
            : periodRepository.findCurrent(user.userId(), now))
        .orElseGet(() -> periodRepository.save(newPeriod(user, policy, now)));
  }

  Optional<ManagedAiUsagePeriod> findCurrent(String userId, Instant now) {
    return periodRepository.findCurrent(userId, now);
  }

  ManagedAiUserContext resolveUser(String runId, RunKeyContext keys) {
    if (keys != null && keys.userId() != null && !keys.userId().isBlank()) {
      return new ManagedAiUserContext(
          keys.userId(),
          blankToNull(keys.orgId()),
          tierEnforcementService.resolveUserTier(keys.userId()));
    }
    PipelineRun run = runId == null ? null : pipelineRunRepository.findById(runId).orElse(null);
    if (run == null || run.getCreatedByUserId() == null || run.getCreatedByUserId().isBlank()) {
      return null;
    }
    String tier =
        run.getUserTier() == null || run.getUserTier().isBlank()
            ? tierEnforcementService.resolveUserTier(run.getCreatedByUserId())
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
}
