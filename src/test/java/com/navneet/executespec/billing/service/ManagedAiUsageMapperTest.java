package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiBalanceResponse;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiUsageEventResponse;
import com.navneet.executespec.billing.entity.ManagedAiUsageEvent;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.cost.RunCostTracker;

import org.junit.jupiter.api.Test;

class ManagedAiUsageMapperTest {

  private final ManagedAiUsageMapper mapper = new ManagedAiUsageMapper();

  @Test
  void mapsSuccessfulCallIntoUsageEvent() {
    ManagedAiUsageEvent event =
        mapper.toEvent(
            "u1",
            "o1",
            period(),
            "agent-session-1",
            "task-1",
            "WorkerAgent",
            null,
            "deepseek",
            "deepseek-v4-pro-native",
            "BALANCED",
            usage(),
            RunKeyContext.Source.USER,
            0,
            ManagedAiRoutingPolicy.FULL,
            ManagedAiRoutingPolicy.VALUE_MODE,
            policy());

    assertThat(event.getId()).isNotBlank();
    assertThat(event.getPeriodId()).isEqualTo("period-1");
    assertThat(event.getUserId()).isEqualTo("u1");
    assertThat(event.getOrgId()).isEqualTo("o1");
    assertThat(event.getRunId()).isEqualTo("agent-session-1");
    assertThat(event.getAgentSessionId()).isEqualTo("agent-session-1");
    assertThat(event.getSourceType()).isEqualTo("BYOK");
    assertThat(event.getStepId()).isEqualTo("task-1");
    assertThat(event.getInputTokens()).isEqualTo(100);
    assertThat(event.getOutputTokens()).isEqualTo(50);
    assertThat(event.getCacheReadTokens()).isEqualTo(10);
    assertThat(event.getCacheWriteTokens()).isEqualTo(5);
    assertThat(event.getReasoningTokens()).isEqualTo(7);
    assertThat(event.getRawCostUsd()).isEqualTo(0.25);
    assertThat(event.getAiCreditsCharged()).isZero();
    assertThat(event.getRoutingStateBefore()).isEqualTo(ManagedAiRoutingPolicy.FULL);
    assertThat(event.getRoutingStateAfter()).isEqualTo(ManagedAiRoutingPolicy.VALUE_MODE);
    assertThat(event.getCreatedAt()).isNotNull();
  }

  @Test
  void mapsBalanceWithRemainingAmounts() {
    ManagedAiUsagePeriod period = period();
    period.setAiCreditsUsed(450);
    period.setRawCogsUsedUsd(7.5);
    period.setFrontierRawUsedUsd(1.25);

    ManagedAiBalanceResponse response =
        mapper.toBalanceResponse(period, policy(), true, ManagedAiRoutingPolicy.VALUE_MODE);

    assertThat(response.aiCreditsRemaining()).isEqualTo(1550);
    assertThat(response.rawCogsRemainingUsd()).isEqualTo(8.5);
    assertThat(response.frontierRawRemainingUsd()).isEqualTo(2.75);
    assertThat(response.routingState()).isEqualTo(ManagedAiRoutingPolicy.VALUE_MODE);
    assertThat(response.managed()).isTrue();
    assertThat(response.retailMultiplier()).isEqualTo(1.25);
  }

  @Test
  void mapsUsageEventResponse() {
    ManagedAiUsageEvent event =
        mapper.toEvent(
            "u1",
            "o1",
            period(),
            "run-1",
            "task-1",
            "ReviewerAgent",
            "review.pass-1",
            "anthropic",
            "claude-sonnet-4-6",
            "BALANCED",
            usage(),
            RunKeyContext.Source.PLATFORM,
            32,
            ManagedAiRoutingPolicy.VALUE_MODE,
            ManagedAiRoutingPolicy.VALUE_MODE,
            policy());

    ManagedAiUsageEventResponse response = mapper.toEventResponse(event);

    assertThat(response.id()).isEqualTo(event.getId());
    assertThat(response.runId()).isEqualTo("run-1");
    assertThat(response.agentSessionId()).isNull();
    assertThat(response.sourceType()).isEqualTo("PLATFORM");
    assertThat(response.provider()).isEqualTo("anthropic");
    assertThat(response.stepId()).isEqualTo("review.pass-1");
    assertThat(response.aiCreditsCharged()).isEqualTo(32);
    assertThat(response.createdAt()).isEqualTo(event.getCreatedAt());
  }

  private static RunCostTracker.CallUsage usage() {
    return new RunCostTracker.CallUsage(
        "deepseek", "deepseek-v4-pro-native", 100, 50, 5, 10, 0.25, 1, 7);
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
        .routingState(ManagedAiRoutingPolicy.FULL)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
