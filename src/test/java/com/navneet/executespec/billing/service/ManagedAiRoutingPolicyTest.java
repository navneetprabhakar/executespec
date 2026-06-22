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

    // Credit-denominated now: VALUE_MODE at 25% of the 2000 grant ($4 of a $16 cap).
    period.setAiCreditsUsed(500);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.VALUE_MODE);

    // Frontier sub-cap remains an internal raw-COGS guardrail.
    period.setFrontierRawUsedUsd(4.0);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.FRONTIER_LOCKED);

    // CHEAP_ONLY at 75% of the grant ($12 of $16).
    period.setAiCreditsUsed(1500);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.CHEAP_ONLY);

    // EXHAUSTED when the credit grant is fully spent.
    period.setAiCreditsUsed(2000);
    assertThat(policy.recomputeState(period)).isEqualTo(ManagedAiRoutingPolicy.EXHAUSTED);
  }

  @Test
  void creditGrantTopUpRaisesTheGate() {
    // The whole point of gating on credits: a top-up raises includedAiCredits and lifts the gate,
    // even though the raw-$ cap ($16) is unchanged.
    ManagedAiUsagePeriod period = period();
    period.setIncludedAiCredits(4000); // topped up from 2000
    period.setAiCreditsUsed(2500); // past the original 2000 grant …
    assertThat(policy.recomputeState(period)).isNotEqualTo(ManagedAiRoutingPolicy.EXHAUSTED);
    period.setAiCreditsUsed(4000); // … exhausted only at the topped-up grant
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
