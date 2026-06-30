package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.entity.ManagedAiUsageEvent;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiUsageEventRepository;
import com.navneet.executespec.billing.repository.ManagedAiUsagePeriodRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ManagedAiUsageServiceTest {

  private ManagedAiUsagePeriodRepository periodRepo;
  private ManagedAiUsageEventRepository eventRepo;
  private ManagedAiModelClassifier modelClassifier;
  private ManagedAiNotificationService notificationService;
  private ManagedAiTierResolver tierResolver;
  private ManagedAiUsageService service;

  @BeforeEach
  void setUp() {
    periodRepo = mock(ManagedAiUsagePeriodRepository.class);
    eventRepo = mock(ManagedAiUsageEventRepository.class);
    modelClassifier = mock(ManagedAiModelClassifier.class);
    notificationService = mock(ManagedAiNotificationService.class);
    tierResolver = mock(ManagedAiTierResolver.class);
    PipelineRunRepository pipelineRunRepository = mock(PipelineRunRepository.class);
    when(tierResolver.resolveUserTier("u1")).thenReturn("PRO");

    ManagedAiProperties properties = new ManagedAiProperties();
    ManagedAiProperties.TierPolicy policy = new ManagedAiProperties.TierPolicy();
    policy.setIncludedCredits(2000);
    policy.setRawCogsCapUsd(16.0);
    policy.setFrontierRawCapUsd(4.0);
    policy.setValueModeAfterRawUsd(4.0);
    policy.setCheapOnlyAfterRawUsd(12.0);
    policy.setHardStopAfterRawUsd(16.0);
    policy.setRetailMultiplier(1.25);
    properties.setTiers(Map.of("PRO", policy));

    when(modelClassifier.managedAiEnabled(anyString())).thenReturn(true);
    when(periodRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    service =
        new ManagedAiUsageService(
            properties,
            eventRepo,
            new ManagedAiCreditCalculator(),
            modelClassifier,
            new ManagedAiRoutingPolicy(),
            new ManagedAiUsageMapper(),
            new ManagedAiUsagePeriodService(
                periodRepo, tierResolver, pipelineRunRepository, notificationService),
            tierResolver);
  }

  @Test
  void platformSuccessfulCallDebitsRawCogsAndCredits() {
    ManagedAiUsagePeriod period = period();
    when(periodRepo.findCurrentForUpdate(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("deepseek-v4-pro-native")).thenReturn("BALANCED");

    service.recordSuccessfulCall(
        "run-1",
        "task-1",
        "WorkerAgent",
        "worker.iteration-1",
        "deepseek",
        "deepseek-v4-pro-native",
        new RunCostTracker.CallUsage(
            "deepseek", "deepseek-v4-pro-native", 100, 50, 0, 10, 0.10, 1, 5),
        keys(),
        RunKeyContext.Source.PLATFORM);

    assertThat(period.getRawCogsUsedUsd()).isEqualTo(0.10);
    assertThat(period.getAiCreditsUsed()).isEqualTo(16);
    ArgumentCaptor<ManagedAiUsageEvent> event = ArgumentCaptor.forClass(ManagedAiUsageEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getSourceType()).isEqualTo("PLATFORM");
    assertThat(event.getValue().getAiCreditsCharged()).isEqualTo(16);
  }

  @Test
  void byokSuccessfulCallWritesAnalyticsEventButDoesNotDebitCredits() {
    ManagedAiUsagePeriod period = period();
    when(periodRepo.findCurrentForUpdate(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("deepseek-v4-pro-native")).thenReturn("BALANCED");

    service.recordSuccessfulCall(
        "run-1",
        "task-1",
        "WorkerAgent",
        "worker.iteration-1",
        "deepseek",
        "deepseek-v4-pro-native",
        new RunCostTracker.CallUsage(
            "deepseek", "deepseek-v4-pro-native", 100, 50, 0, 10, 0.10, 1, 5),
        keys(),
        RunKeyContext.Source.USER);

    assertThat(period.getRawCogsUsedUsd()).isZero();
    assertThat(period.getAiCreditsUsed()).isZero();
    ArgumentCaptor<ManagedAiUsageEvent> event = ArgumentCaptor.forClass(ManagedAiUsageEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getSourceType()).isEqualTo("BYOK");
    assertThat(event.getValue().getAiCreditsCharged()).isZero();
  }

  @Test
  void cheapOnlyStateStepsDownToSameProviderCheapModel() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(1500); // CHEAP_ONLY = 75% of the 2000 grant
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("deepseek-v4-pro-native")).thenReturn("BALANCED");
    when(modelClassifier.costClass("deepseek-v4-flash-native")).thenReturn("CHEAP");
    when(modelClassifier.sameProviderStepDown("deepseek", "deepseek-v4-pro-native"))
        .thenReturn(
            Optional.of(
                new ManagedAiModelClassifier.ModelTarget("deepseek", "deepseek-v4-flash-native")));

    ManagedAiUsageService.CallAuthorization auth =
        service.authorizeCall(
            "run-1",
            "deepseek",
            "deepseek-v4-pro-native",
            "WorkerAgent",
            keys(),
            RunKeyContext.Source.PLATFORM);

    assertThat(auth.modelChanged()).isTrue();
    assertThat(auth.model()).isEqualTo("deepseek-v4-flash-native");
    assertThat(auth.routingState()).isEqualTo(ManagedAiUsageService.CHEAP_ONLY);
  }

  @Test
  void valueModeStepsCriticalPlannerUpToSameProviderFrontierModel() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(500); // VALUE_MODE = 25% of the 2000 grant
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("claude-sonnet-4-6")).thenReturn("BALANCED");
    when(modelClassifier.costClass("claude-opus-4-6")).thenReturn("FRONTIER");
    when(modelClassifier.sameProviderStepUp("anthropic", "claude-sonnet-4-6"))
        .thenReturn(
            Optional.of(new ManagedAiModelClassifier.ModelTarget("anthropic", "claude-opus-4-6")));

    ManagedAiUsageService.CallAuthorization auth =
        service.authorizeCall(
            "run-1",
            "anthropic",
            "claude-sonnet-4-6",
            "OutlinePlannerAgent",
            keys(),
            RunKeyContext.Source.PLATFORM);

    assertThat(auth.modelChanged()).isTrue();
    assertThat(auth.model()).isEqualTo("claude-opus-4-6");
    assertThat(auth.routingState()).isEqualTo(ManagedAiUsageService.VALUE_MODE);
    assertThat(auth.reason()).contains("stepped up");
  }

  @Test
  void valueModeDoesNotStepWorkerUpToFrontier() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(500); // VALUE_MODE = 25% of the 2000 grant
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("claude-sonnet-4-6")).thenReturn("BALANCED");

    ManagedAiUsageService.CallAuthorization auth =
        service.authorizeCall(
            "run-1",
            "anthropic",
            "claude-sonnet-4-6",
            "WorkerAgent",
            keys(),
            RunKeyContext.Source.PLATFORM);

    assertThat(auth.modelChanged()).isFalse();
    assertThat(auth.model()).isEqualTo("claude-sonnet-4-6");
    verify(modelClassifier, never()).sameProviderStepUp(anyString(), anyString());
  }

  @Test
  void debitTransitionToValueModeNotifiesAndNextCriticalPlannerStepsUpMidRun() {
    ManagedAiUsagePeriod period = period();
    period.setRawCogsUsedUsd(3.95);
    period.setAiCreditsUsed(487); // +1 call (13 cr) crosses VALUE_MODE at 500
    when(periodRepo.findCurrentForUpdate(anyString(), any())).thenReturn(Optional.of(period));
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("claude-sonnet-4-6")).thenReturn("BALANCED");
    when(modelClassifier.costClass("claude-opus-4-6")).thenReturn("FRONTIER");
    when(modelClassifier.sameProviderStepUp("anthropic", "claude-sonnet-4-6"))
        .thenReturn(
            Optional.of(new ManagedAiModelClassifier.ModelTarget("anthropic", "claude-opus-4-6")));

    service.recordSuccessfulCall(
        "run-1",
        "task-1",
        "WorkerAgent",
        "worker.iteration-17",
        "anthropic",
        "claude-sonnet-4-6",
        new RunCostTracker.CallUsage("anthropic", "claude-sonnet-4-6", 1000, 500, 0, 0, 0.10, 1, 5),
        keys(),
        RunKeyContext.Source.PLATFORM);

    assertThat(period.getRawCogsUsedUsd()).isCloseTo(4.05, offset(0.000001));
    assertThat(period.getAiCreditsUsed()).isEqualTo(503);
    assertThat(period.getRoutingState()).isEqualTo(ManagedAiUsageService.VALUE_MODE);
    verify(notificationService)
        .notifyTransition(period, ManagedAiUsageService.FULL, ManagedAiUsageService.VALUE_MODE);
    ArgumentCaptor<ManagedAiUsageEvent> event = ArgumentCaptor.forClass(ManagedAiUsageEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getRoutingStateBefore()).isEqualTo(ManagedAiUsageService.FULL);
    assertThat(event.getValue().getRoutingStateAfter()).isEqualTo(ManagedAiUsageService.VALUE_MODE);
    assertThat(event.getValue().getAiCreditsCharged()).isEqualTo(16);

    ManagedAiUsageService.CallAuthorization auth =
        service.authorizeCall(
            "run-1",
            "anthropic",
            "claude-sonnet-4-6",
            "OutlinePlannerAgent",
            keys(),
            RunKeyContext.Source.PLATFORM);

    assertThat(auth.routingState()).isEqualTo(ManagedAiUsageService.VALUE_MODE);
    assertThat(auth.modelChanged()).isTrue();
    assertThat(auth.provider()).isEqualTo("anthropic");
    assertThat(auth.model()).isEqualTo("claude-opus-4-6");
    assertThat(auth.reason()).contains("stepped up");
  }

  @Test
  void exhaustedStateBlocksBeforeProviderCall() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(2000); // EXHAUSTED — grant fully spent
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("deepseek-v4-pro-native")).thenReturn("BALANCED");
    when(modelClassifier.sameProviderStepDown(anyString(), anyString()))
        .thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.authorizeCall(
                    "run-1",
                    "deepseek",
                    "deepseek-v4-pro-native",
                    "WorkerAgent",
                    keys(),
                    RunKeyContext.Source.PLATFORM))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("EXHAUSTED");
    verify(eventRepo, never()).save(any());
  }

  @Test
  void debitTransitionNotifiesAndNextCallStepsDownMidRun() {
    ManagedAiUsagePeriod period = period();
    period.setRawCogsUsedUsd(11.95);
    period.setAiCreditsUsed(1487); // +1 call (13 cr) crosses CHEAP_ONLY at 1500
    when(periodRepo.findCurrentForUpdate(anyString(), any())).thenReturn(Optional.of(period));
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));
    when(modelClassifier.costClass("deepseek-v4-pro-native")).thenReturn("BALANCED");
    when(modelClassifier.costClass("deepseek-v4-flash-native")).thenReturn("CHEAP");
    when(modelClassifier.sameProviderStepDown("deepseek", "deepseek-v4-pro-native"))
        .thenReturn(
            Optional.of(
                new ManagedAiModelClassifier.ModelTarget("deepseek", "deepseek-v4-flash-native")));

    service.recordSuccessfulCall(
        "run-1",
        "task-1",
        "WorkerAgent",
        "worker.iteration-42",
        "deepseek",
        "deepseek-v4-pro-native",
        new RunCostTracker.CallUsage(
            "deepseek", "deepseek-v4-pro-native", 1000, 500, 0, 0, 0.10, 1, 5),
        keys(),
        RunKeyContext.Source.PLATFORM);

    assertThat(period.getRawCogsUsedUsd()).isCloseTo(12.05, offset(0.000001));
    assertThat(period.getRoutingState()).isEqualTo(ManagedAiUsageService.CHEAP_ONLY);
    verify(notificationService)
        .notifyTransition(
            period, ManagedAiUsageService.VALUE_MODE, ManagedAiUsageService.CHEAP_ONLY);
    ArgumentCaptor<ManagedAiUsageEvent> event = ArgumentCaptor.forClass(ManagedAiUsageEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getRoutingStateBefore())
        .isEqualTo(ManagedAiUsageService.VALUE_MODE);
    assertThat(event.getValue().getRoutingStateAfter()).isEqualTo(ManagedAiUsageService.CHEAP_ONLY);
    assertThat(event.getValue().getAiCreditsCharged()).isEqualTo(16);

    ManagedAiUsageService.CallAuthorization auth =
        service.authorizeCall(
            "run-1",
            "deepseek",
            "deepseek-v4-pro-native",
            "WorkerAgent",
            keys(),
            RunKeyContext.Source.PLATFORM);

    assertThat(auth.routingState()).isEqualTo(ManagedAiUsageService.CHEAP_ONLY);
    assertThat(auth.modelChanged()).isTrue();
    assertThat(auth.provider()).isEqualTo("deepseek");
    assertThat(auth.model()).isEqualTo("deepseek-v4-flash-native");
    assertThat(auth.reason()).contains("stepped down");
  }

  // ---- Run-count retirement (2026-06-22): submission-time credit gate + entitlements summary ----

  @Test
  void assertCanStartRunThrows402WhenExhausted() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(2000); // grant fully spent -> EXHAUSTED
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.assertCanStartRun(userDetails(), false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("exhausted");
  }

  @Test
  void assertCanStartRunAllowsWhenBudgetRemains() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(50); // well under thresholds -> FULL
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));

    service.assertCanStartRun(userDetails(), false); // no throw
  }

  @Test
  void assertCanStartRunBypassesGateForOwnKeys() {
    service.assertCanStartRun(userDetails(), true); // pure BYOK -> exempt, no period lookup
    verify(periodRepo, never()).findCurrent(anyString(), any());
  }

  @Test
  void assertCanStartRunAllowsUngatedTier() {
    when(tierResolver.resolveUserTier("u1")).thenReturn("ENTERPRISE"); // no managed policy
    service.assertCanStartRun(userDetails(), false); // ungated -> no throw
    verify(periodRepo, never()).findCurrent(anyString(), any());
  }

  @Test
  void creditSummaryReportsManagedBalanceForManagedTier() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(500);
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(period));

    ManagedAiUsageService.CreditSummary cs = service.creditSummary("u1", "PRO");

    assertThat(cs.managed()).isTrue();
    assertThat(cs.creditsIncluded()).isEqualTo(2000);
    assertThat(cs.creditsUsed()).isEqualTo(500);
    assertThat(cs.creditsRemaining()).isEqualTo(1500);
  }

  @Test
  void creditSummaryReflectsCurrentPolicyGrantWhenPeriodSnapshotIsStale() {
    ManagedAiUsagePeriod stale = period();
    stale.setIncludedAiCredits(0); // period created under an older 0-credit policy
    when(periodRepo.findCurrent(anyString(), any())).thenReturn(Optional.of(stale));

    ManagedAiUsageService.CreditSummary cs = service.creditSummary("u1", "PRO");

    assertThat(cs.creditsIncluded()).isEqualTo(2000); // current PRO policy grant, not the stale 0
    assertThat(cs.creditsRemaining()).isEqualTo(2000);
  }

  @Test
  void creditSummaryReportsUngatedForEnterprise() {
    ManagedAiUsageService.CreditSummary cs = service.creditSummary("u1", "ENTERPRISE");

    assertThat(cs.managed()).isFalse();
    assertThat(cs.creditsRemaining()).isEqualTo(-1);
  }

  private static AppUserDetails userDetails() {
    AppUser u = new AppUser();
    u.setId("u1");
    u.setOrgId("o1");
    u.setTier("PRO");
    return new AppUserDetails(u, null);
  }

  private static RunKeyContext keys() {
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
        "u1",
        "o1");
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
        .routingState(ManagedAiUsageService.FULL)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
