package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ManagedAiRoutingPolicyTest {

  private final ManagedAiRoutingPolicy policy = new ManagedAiRoutingPolicy();

  @Test
  void recomputesRoutingStateBySpendThresholdOrder() {
    ManagedAiUsagePeriod period = period();

    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.FULL);

    period.setRawCogsUsedUsd(4.0);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.VALUE_MODE);

    period.setFrontierRawUsedUsd(4.0);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.FRONTIER_LOCKED);

    period.setRawCogsUsedUsd(12.0);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.CHEAP_ONLY);

    period.setRawCogsUsedUsd(16.0);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.EXHAUSTED);
  }

  @Test
  void appliesStateModelClassAndAgentRoleRules() {
    assertThat(policy.isAllowed(ManagedAiRoutingPolicy.FULL, "FRONTIER", "WorkerAgent")).isTrue();
    assertThat(policy.isAllowed(ManagedAiRoutingPolicy.CHEAP_ONLY, "CHEAP", "WorkerAgent"))
        .isTrue();
    assertThat(policy.isAllowed(ManagedAiRoutingPolicy.CHEAP_ONLY, "BALANCED", "WorkerAgent"))
        .isFalse();
    assertThat(policy.isAllowed(ManagedAiRoutingPolicy.FRONTIER_LOCKED, "FRONTIER", "Reviewer"))
        .isFalse();
    assertThat(policy.isAllowed(ManagedAiRoutingPolicy.VALUE_MODE, "FRONTIER", "WorkerAgent"))
        .isFalse();
    assertThat(policy.isAllowed(ManagedAiRoutingPolicy.VALUE_MODE, "FRONTIER", "ReviewerAgent"))
        .isTrue();
    assertThat(policy.isAllowed(ManagedAiRoutingPolicy.EXHAUSTED, "CHEAP", "ReviewerAgent"))
        .isFalse();
  }

  @Test
  void stepUpIsLimitedToCriticalAgentsWithFrontierBudget() {
    ManagedAiUsagePeriod period = period();
    period.setRawCogsUsedUsd(4.0);

    assertThat(
            policy.shouldStepUp(
                ManagedAiRoutingPolicy.VALUE_MODE, "BALANCED", "OutlinePlannerAgent", period))
        .isTrue();
    assertThat(
            policy.shouldStepUp(
                ManagedAiRoutingPolicy.VALUE_MODE, "BALANCED", "WorkerAgent", period))
        .isFalse();
    assertThat(
            policy.shouldStepUp(
                ManagedAiRoutingPolicy.VALUE_MODE, "FRONTIER", "ReviewerAgent", period))
        .isFalse();

    period.setFrontierRawUsedUsd(4.0);
    assertThat(
            policy.shouldStepUp(
                ManagedAiRoutingPolicy.VALUE_MODE, "BALANCED", "ReviewerAgent", period))
        .isFalse();
  }

  @Test
  void blockedUsesPaymentRequired() {
    ResponseStatusException error = policy.blocked(ManagedAiRoutingPolicy.EXHAUSTED, "BALANCED");

    assertThat(error.getStatusCode().value()).isEqualTo(402);
    assertThat(error.getReason()).contains("EXHAUSTED").contains("BALANCED");
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
