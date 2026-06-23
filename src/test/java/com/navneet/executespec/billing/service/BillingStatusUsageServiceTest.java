package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.dto.BillingStatusResponse;
import com.navneet.executespec.billing.entity.BillingPlanLimit;
import com.navneet.executespec.billing.entity.BillingUsage;
import com.navneet.executespec.billing.entity.RunTopup;
import com.navneet.executespec.billing.repository.RunTopupRepository;

import org.junit.jupiter.api.Test;

class BillingStatusUsageServiceTest {

  private final UsageTrackingService usageService = mock(UsageTrackingService.class);
  private final RunTopupRepository runTopupRepository = mock(RunTopupRepository.class);
  private final AppUserRepository userRepository = mock(AppUserRepository.class);
  private final BillingStatusUsageService service =
      new BillingStatusUsageService(usageService, runTopupRepository, userRepository);

  @Test
  void mapsPlanLimitsToUsageRowsAndAddsBonusRunsToRunsLimit() {
    when(usageService.getCurrentUsage("u1"))
        .thenReturn(
            List.of(
                BillingUsage.builder().metric("RUNS").currentValue(8).build(),
                BillingUsage.builder().metric("PROJECTS").currentValue(2).build()));
    BillingStatusResponse.BonusRunsInfo bonusRuns =
        new BillingStatusResponse.BonusRunsInfo(5, List.of());

    List<BillingStatusResponse.UsageInfo> usage =
        service.usageInfo(
            "u1",
            List.of(limit("RUNS", 10), limit("PROJECTS", 3), limit("STORAGE_MB", 0)),
            bonusRuns);

    assertThat(usage)
        .extracting(BillingStatusResponse.UsageInfo::metric)
        .containsExactly("RUNS", "PROJECTS", "STORAGE_MB");
    assertThat(usage.get(0).current()).isEqualTo(8);
    assertThat(usage.get(0).max()).isEqualTo(15);
    assertThat(usage.get(0).pctUsed()).isEqualTo((8.0 / 15.0) * 100.0);
    assertThat(usage.get(1).pctUsed()).isEqualTo((2.0 / 3.0) * 100.0);
    assertThat(usage.get(2).pctUsed()).isZero();
  }

  @Test
  void bonusRunsIncludeUserOrgAndActiveTopups() {
    AppUser user = AppUser.builder().id("u1").orgId("o1").build();
    RunTopup topup =
        RunTopup.builder()
            .id(UUID.randomUUID())
            .quantity(10)
            .remaining(4)
            .source("PURCHASE")
            .status("ACTIVE")
            .expiresAt(Instant.parse("2026-07-01T00:00:00Z"))
            .createdAt(Instant.parse("2026-06-01T00:00:00Z"))
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(user));
    when(runTopupRepository.sumRemainingByUserId(any(), any())).thenReturn(4L);
    when(runTopupRepository.sumRemainingByOrgId(any(), any())).thenReturn(6L);
    when(runTopupRepository.findActiveByUserId(any(), any())).thenReturn(List.of(topup));

    BillingStatusResponse.BonusRunsInfo info = service.bonusRunsInfo("u1");

    assertThat(info.available()).isEqualTo(10);
    assertThat(info.topups()).hasSize(1);
    assertThat(info.topups().get(0).remaining()).isEqualTo(4);
  }

  @Test
  void bonusRunsSkipsOrgLookupWhenUserHasNoOrg() {
    when(userRepository.findById("u1")).thenReturn(Optional.of(AppUser.builder().id("u1").build()));
    when(runTopupRepository.sumRemainingByUserId(any(), any())).thenReturn(0L);
    when(runTopupRepository.findActiveByUserId(any(), any())).thenReturn(List.of());

    assertThat(service.bonusRunsInfo("u1")).isNull();
    verify(runTopupRepository, never()).sumRemainingByOrgId(any(), any());
  }

  private static BillingPlanLimit limit(String metric, long max) {
    return BillingPlanLimit.builder().metric(metric).maxValue(max).build();
  }
}
