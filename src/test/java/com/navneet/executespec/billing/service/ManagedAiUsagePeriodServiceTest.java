package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiUsagePeriodRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedAiUsagePeriodServiceTest {

  private ManagedAiUsagePeriodRepository periodRepository;
  private TierEnforcementService tierEnforcementService;
  private PipelineRunRepository pipelineRunRepository;
  private ManagedAiNotificationService notificationService;
  private ManagedAiUsagePeriodService service;

  @BeforeEach
  void setUp() {
    periodRepository = mock(ManagedAiUsagePeriodRepository.class);
    tierEnforcementService = mock(TierEnforcementService.class);
    pipelineRunRepository = mock(PipelineRunRepository.class);
    notificationService = mock(ManagedAiNotificationService.class);
    when(periodRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    service =
        new ManagedAiUsagePeriodService(
            periodRepository, tierEnforcementService, pipelineRunRepository, notificationService);
  }

  @Test
  void createsUtcMonthlyPeriodWhenNoCurrentPeriodExists() {
    Instant now = Instant.parse("2026-06-20T18:30:00Z");
    when(periodRepository.findCurrent("u1", now)).thenReturn(Optional.empty());

    ManagedAiUsagePeriod period = service.currentOrCreate(user(), policy(), now, false);

    assertThat(period.getId()).isNotBlank();
    assertThat(period.getUserId()).isEqualTo("u1");
    assertThat(period.getOrgId()).isEqualTo("o1");
    assertThat(period.getTier()).isEqualTo("PRO");
    assertThat(period.getPeriodStart()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    assertThat(period.getPeriodEnd()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    assertThat(period.getIncludedAiCredits()).isEqualTo(2000);
    assertThat(period.getHardStopAfterRawUsd()).isEqualTo(16.0);
    assertThat(period.getRoutingState()).isEqualTo(ManagedAiRoutingPolicy.FULL);
    verify(periodRepository).save(period);
  }

  @Test
  void lockedLookupUsesForUpdateRepositoryMethod() {
    Instant now = Instant.parse("2026-06-20T00:00:00Z");
    ManagedAiUsagePeriod existing = period();
    when(periodRepository.findCurrentForUpdate("u1", now)).thenReturn(Optional.of(existing));

    ManagedAiUsagePeriod period = service.currentOrCreate(user(), policy(), now, true);

    assertThat(period).isSameAs(existing);
    verify(periodRepository, never()).findCurrent("u1", now);
  }

  @Test
  void resolvesUserFromRunKeyContextBeforePipelineRunFallback() {
    when(tierEnforcementService.resolveUserTier("u1")).thenReturn("PRO");

    ManagedAiUserContext user = service.resolveUser("run-1", keys("u1", " "));

    assertThat(user).isEqualTo(new ManagedAiUserContext("u1", null, "PRO"));
    verify(pipelineRunRepository, never()).findById(anyString());
  }

  @Test
  void resolvesUserFromPipelineRunAndNormalizesStoredTier() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .createdByUserId("u2")
            .orgId("o2")
            .userTier(" pro ")
            .build();
    when(pipelineRunRepository.findById("run-1")).thenReturn(Optional.of(run));

    ManagedAiUserContext user = service.resolveUser("run-1", null);

    assertThat(user).isEqualTo(new ManagedAiUserContext("u2", "o2", "PRO"));
    verify(tierEnforcementService, never()).resolveUserTier(anyString());
  }

  @Test
  void resolvesMissingRunTierThroughTierService() {
    PipelineRun run =
        PipelineRun.builder().runId("run-1").createdByUserId("u2").orgId("o2").build();
    when(pipelineRunRepository.findById("run-1")).thenReturn(Optional.of(run));
    when(tierEnforcementService.resolveUserTier("u2")).thenReturn("TEAM");

    ManagedAiUserContext user = service.resolveUser("run-1", null);

    assertThat(user).isEqualTo(new ManagedAiUserContext("u2", "o2", "TEAM"));
  }

  @Test
  void saveWithTransitionPersistsMutationAndNotifiesOnlyOnChangedState() {
    ManagedAiUsagePeriod period = period();
    period.setRawCogsUsedUsd(12.5);

    service.saveWithTransition(
        period, ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(periodRepository).save(period);
    verify(notificationService)
        .notifyTransition(
            period, ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY);

    ManagedAiUsagePeriod sameState = period();
    service.saveWithTransition(
        sameState, ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.VALUE_MODE);

    verify(periodRepository).save(sameState);
    verify(notificationService, never())
        .notifyTransition(
            sameState, ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.VALUE_MODE);
  }

  private static RunKeyContext keys(String userId, String orgId) {
    return new RunKeyContext(
        null,
        null,
        null,
        null,
        null,
        null,
        RunKeyContext.Source.PLATFORM,
        RunKeyContext.Source.PLATFORM,
        RunKeyContext.Source.PLATFORM,
        RunKeyContext.Source.PLATFORM,
        RunKeyContext.Source.PLATFORM,
        RunKeyContext.Source.PLATFORM,
        userId,
        orgId);
  }

  private static ManagedAiUserContext user() {
    return new ManagedAiUserContext("u1", "o1", "PRO");
  }

  private static ManagedAiProperties.TierPolicy policy() {
    ManagedAiProperties.TierPolicy policy = new ManagedAiProperties.TierPolicy();
    policy.setIncludedCredits(2000);
    policy.setRawCogsCapUsd(16.0);
    policy.setFrontierRawCapUsd(4.0);
    policy.setValueModeAfterRawUsd(4.0);
    policy.setCheapOnlyAfterRawUsd(12.0);
    policy.setHardStopAfterRawUsd(16.0);
    policy.setRetailMultiplier(1.25);
    return policy;
  }

  private static ManagedAiUsagePeriod period() {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    return ManagedAiUsagePeriod.builder()
        .id("period-1")
        .userId("u1")
        .orgId("o1")
        .tier("PRO")
        .periodStart(now)
        .periodEnd(now.plusSeconds(31L * 24 * 3600))
        .includedAiCredits(2000)
        .aiCreditsUsed(0)
        .rawCogsCapUsd(16.0)
        .rawCogsUsedUsd(0.0)
        .frontierRawCapUsd(4.0)
        .frontierRawUsedUsd(0.0)
        .valueModeAfterRawUsd(4.0)
        .cheapOnlyAfterRawUsd(12.0)
        .hardStopAfterRawUsd(16.0)
        .routingState(ManagedAiRoutingPolicy.VALUE_MODE)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
