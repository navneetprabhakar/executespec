package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.Test;

class RunCostTrackerOpenRouterPricingParityTest {

  @Test
  void ownerModelUsesIdenticalPricingForEstimateAndActualAccounting() {
    ModelPricingRepository pricing = mock(ModelPricingRepository.class);
    ModelPricing sonnet = new ModelPricing();
    sonnet.setInputCostPer1m(3.0);
    sonnet.setOutputCostPer1m(15.0);
    when(pricing.findByIdProviderAndIdModel("openrouter", "claude-sonnet-5"))
        .thenReturn(Optional.empty());
    when(pricing.findByIdProviderAndIdModel("anthropic", "claude-sonnet-5"))
        .thenReturn(Optional.of(sonnet));
    RunCostTracker tracker = new RunCostTracker(pricing, mock(ModelResolver.class));

    double estimatedTokens =
        tracker.estimateTokenCostUsd("openrouter", "claude-sonnet-5", 1_000_000, 100_000);
    double accountedTokens =
        tracker.computeCost("openrouter", "claude-sonnet-5", 1_000_000, 100_000);
    RunCostTracker.Estimate runEstimate =
        tracker.estimateRunTierAware(
            "openrouter", List.of("MEDIUM"), PlanStrategy.SMALL, 1, false, "claude-sonnet-5");

    assertThat(estimatedTokens).isEqualTo(accountedTokens).isEqualTo(4.5);
    assertThat(runEstimate.lowUsd()).isGreaterThan(0.1);
    assertThat(runEstimate.highUsd()).isGreaterThan(runEstimate.lowUsd());
  }

  @Test
  void anUnpricedPinnedRouteFailsClosedInsteadOfUsingTheMinimumFloor() {
    RunCostTracker tracker =
        new RunCostTracker(mock(ModelPricingRepository.class), mock(ModelResolver.class));

    assertThatThrownBy(
            () ->
                tracker.estimateRunTierAware(
                    "openrouter",
                    List.of("MEDIUM"),
                    PlanStrategy.SMALL,
                    1,
                    false,
                    "unknown-owner-model"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MODEL_PRICING_UNAVAILABLE");
  }
}
