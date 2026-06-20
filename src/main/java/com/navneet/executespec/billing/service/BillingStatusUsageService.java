package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.dto.BillingStatusResponse;
import com.navneet.executespec.billing.entity.BillingPlanLimit;
import com.navneet.executespec.billing.entity.BillingUsage;
import com.navneet.executespec.billing.entity.RunTopup;
import com.navneet.executespec.billing.repository.RunTopupRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BillingStatusUsageService {

  private final UsageTrackingService usageService;
  private final RunTopupRepository runTopupRepository;
  private final AppUserRepository userRepository;

  public List<BillingStatusResponse.UsageInfo> usageInfo(
      String userId, List<BillingPlanLimit> limits, BillingStatusResponse.BonusRunsInfo bonusRuns) {
    List<BillingUsage> usageList = usageService.getCurrentUsage(userId);
    long totalBonus = bonusRuns == null ? 0 : bonusRuns.available();
    return limits.stream().map(limit -> toUsageInfo(limit, usageList, totalBonus)).toList();
  }

  public BillingStatusResponse.BonusRunsInfo bonusRunsInfo(String userId) {
    Instant now = Instant.now();
    long userBonus = runTopupRepository.sumRemainingByUserId(userId, now);
    AppUser statusUser = userRepository.findById(userId).orElse(null);
    long orgBonus =
        statusUser != null && statusUser.getOrgId() != null
            ? runTopupRepository.sumRemainingByOrgId(statusUser.getOrgId(), now)
            : 0;
    long totalBonus = userBonus + orgBonus;

    List<BillingStatusResponse.TopupInfo> topupInfos =
        runTopupRepository.findActiveByUserId(userId, now).stream()
            .map(BillingStatusUsageService::toTopupInfo)
            .toList();
    return totalBonus > 0 || !topupInfos.isEmpty()
        ? new BillingStatusResponse.BonusRunsInfo(totalBonus, topupInfos)
        : null;
  }

  private static BillingStatusResponse.UsageInfo toUsageInfo(
      BillingPlanLimit limit, List<BillingUsage> usageList, long totalBonus) {
    long current =
        usageList.stream()
            .filter(u -> u.getMetric().equals(limit.getMetric()))
            .findFirst()
            .map(BillingUsage::getCurrentValue)
            .orElse(0L);
    long max = limit.getMaxValue();
    if ("RUNS".equals(limit.getMetric()) && totalBonus > 0) {
      max += totalBonus;
    }
    double pct = max > 0 ? (double) current / max * 100 : 0;
    return new BillingStatusResponse.UsageInfo(limit.getMetric(), current, max, pct);
  }

  private static BillingStatusResponse.TopupInfo toTopupInfo(RunTopup topup) {
    return new BillingStatusResponse.TopupInfo(
        topup.getId(),
        topup.getQuantity(),
        topup.getRemaining(),
        topup.getSource(),
        topup.getStatus(),
        topup.getExpiresAt(),
        topup.getCreatedAt());
  }
}
