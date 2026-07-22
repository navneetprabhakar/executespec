package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;

/**
 * RR-066 (2026-07-22) — a LOW-complexity worker must be estimated at the WORKHORSE tier, because
 * that is the executor's default worker route (LOW only drops to LIGHTWEIGHT on explicit opt-in).
 * The estimator previously priced LOW at LIGHTWEIGHT, materially understating LOW-run estimates,
 * credit authorization and the hard cost cap. This test proves the LOW worker cost tracks the
 * WORKHORSE tier and is insensitive to the LIGHTWEIGHT price.
 */
class RunCostTrackerLowTierEstimateTest {

  private static final String PLANNING_MODEL = "planning-model";
  private static final String WORKHORSE_MODEL = "workhorse-model";
  private static final String LIGHTWEIGHT_MODEL = "lightweight-model";

  private static RunCostTracker tracker(double workhorseRate, double lightweightRate) {
    ModelPricingRepository pricingRepo = mock(ModelPricingRepository.class);
    ModelResolver resolver = mock(ModelResolver.class);

    stubPricing(pricingRepo, PLANNING_MODEL, 3.0, 15.0);
    stubPricing(pricingRepo, WORKHORSE_MODEL, workhorseRate, workhorseRate);
    stubPricing(pricingRepo, LIGHTWEIGHT_MODEL, lightweightRate, lightweightRate);

    stubResolver(resolver, AgentTier.PLANNING, PLANNING_MODEL);
    stubResolver(resolver, AgentTier.WORKHORSE, WORKHORSE_MODEL);
    stubResolver(resolver, AgentTier.LIGHTWEIGHT, LIGHTWEIGHT_MODEL);

    return new RunCostTracker(pricingRepo, resolver);
  }

  private static double lowEstimate(RunCostTracker tracker) {
    return tracker
        .estimateRunTierAware("anthropic", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, false)
        .lowUsd();
  }

  @Test
  void lowWorkerCostTracksWorkhorseTierAndIsInsensitiveToLightweightPrice() {
    double base = lowEstimate(tracker(1.0, 1.0));
    // Raise ONLY the WORKHORSE rate 20x. A LOW task priced at WORKHORSE must move a lot.
    double workhorseRaised = lowEstimate(tracker(20.0, 1.0));
    // Raise ONLY the LIGHTWEIGHT rate 20x. If LOW were still priced at LIGHTWEIGHT this would move
    // by the full worker band; after RR-066 only the small classifier/coherence overhead reacts.
    double lightweightRaised = lowEstimate(tracker(1.0, 20.0));

    double workhorseDelta = workhorseRaised - base;
    double lightweightDelta = lightweightRaised - base;

    assertThat(workhorseDelta)
        .as("raising the WORKHORSE rate must materially raise a LOW estimate (RR-066)")
        .isGreaterThan(0.0);
    assertThat(workhorseDelta)
        .as("the LOW worker band responds to WORKHORSE far more than to LIGHTWEIGHT")
        .isGreaterThan(lightweightDelta * 3.0);
  }

  private static void stubPricing(
      ModelPricingRepository repo, String model, double in, double out) {
    ModelPricing mp = new ModelPricing();
    mp.setInputCostPer1m(in);
    mp.setOutputCostPer1m(out);
    lenient().when(repo.findByIdProviderAndIdModel("anthropic", model)).thenReturn(Optional.of(mp));
  }

  private static void stubResolver(ModelResolver resolver, AgentTier tier, String model) {
    ResolvedModel rm =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.ANTHROPIC)
            .actualProvider(LlmProvider.ANTHROPIC)
            .model(model)
            .agentTier(tier)
            .fallback(false)
            .build();
    lenient()
        .when(resolver.resolve(anyString(), eq(LlmProvider.ANTHROPIC), eq(tier)))
        .thenReturn(rm);
    lenient().when(resolver.resolve(anyString(), eq(LlmProvider.ANTHROPIC))).thenReturn(rm);
  }
}
