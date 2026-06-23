package com.navneet.executespec.billing.dto;

import java.time.Instant;
import java.util.List;

public final class ManagedAiDtos {

  private ManagedAiDtos() {}

  public record ManagedAiBalanceResponse(
      String userId,
      String orgId,
      String tier,
      String routingState,
      Instant periodStart,
      Instant periodEnd,
      long includedAiCredits,
      long aiCreditsUsed,
      long aiCreditsRemaining,
      double rawCogsCapUsd,
      double rawCogsUsedUsd,
      double rawCogsRemainingUsd,
      double frontierRawCapUsd,
      double frontierRawUsedUsd,
      double frontierRawRemainingUsd,
      double valueModeAfterRawUsd,
      double cheapOnlyAfterRawUsd,
      double hardStopAfterRawUsd,
      boolean managed,
      double retailMultiplier) {}

  public record ManagedAiUsageEventResponse(
      String id,
      String periodId,
      String runId,
      String agentSessionId,
      String sourceType,
      String provider,
      String model,
      String modelClass,
      String agentRole,
      String stepId,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      long reasoningTokens,
      double rawCostUsd,
      double retailMultiplier,
      long aiCreditsCharged,
      String routingStateBefore,
      String routingStateAfter,
      Instant createdAt) {}

  public record ManagedAiPolicyResponse(
      boolean enabled,
      String tier,
      long includedCredits,
      double rawCogsCapUsd,
      double frontierRawCapUsd,
      double valueModeAfterRawUsd,
      double cheapOnlyAfterRawUsd,
      double hardStopAfterRawUsd,
      double retailMultiplier,
      boolean concurrencyEnforced,
      boolean rateLimitsEnabled,
      List<Integer> notificationThresholdsPct,
      String creditDefinition) {}
}
