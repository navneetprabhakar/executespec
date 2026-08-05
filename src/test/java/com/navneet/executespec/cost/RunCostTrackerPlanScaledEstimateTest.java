package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Collections;
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
 * P2 route-templated-estimate fix (2026-08-05). The plan-gate estimate must reflect the ACTUAL
 * approved plan: scale with task count and per-task complexity tier (bands anchored to
 * harness/WorkerTierBudgetPolicy budgets), price at the route's real per-token matrix rates, stay
 * deterministic for the same plan, and never fall to zero. The former P75 bands stacked with a flat
 * 3.0x high multiplier overshot observed single-task DeepSeek runs ($0.0100-$0.0129 actual) by
 * 10-15x; the plan-scaled tail multiplier brings small standalone runs to roughly 3x while large
 * multi-repo project plans keep the full 3.0x tail cap.
 */
class RunCostTrackerPlanScaledEstimateTest {

  private static final String DEEPSEEK_MODEL = "deepseek-v4-pro-native";
  private static final double DEEPSEEK_IN = 0.435;
  private static final double DEEPSEEK_OUT = 0.87;

  private static final String SONNET = "claude-sonnet-4-6";
  private static final double SONNET_IN = 3.0;
  private static final double SONNET_OUT = 15.0;

  /** DeepSeek-shaped tracker: every tier resolves to one cheap native model. */
  private static RunCostTracker deepseekTracker() {
    ModelPricingRepository pricingRepo = mock(ModelPricingRepository.class);
    ModelResolver resolver = mock(ModelResolver.class);
    stubPricing(pricingRepo, "deepseek", DEEPSEEK_MODEL, DEEPSEEK_IN, DEEPSEEK_OUT);
    for (AgentTier tier : AgentTier.values()) {
      stubResolver(resolver, LlmProvider.DEEPSEEK, tier, DEEPSEEK_MODEL);
    }
    return new RunCostTracker(pricingRepo, resolver);
  }

  /** Anthropic-shaped tracker: every tier resolves to Sonnet-class rates. */
  private static RunCostTracker anthropicTracker() {
    ModelPricingRepository pricingRepo = mock(ModelPricingRepository.class);
    ModelResolver resolver = mock(ModelResolver.class);
    stubPricing(pricingRepo, "anthropic", SONNET, SONNET_IN, SONNET_OUT);
    for (AgentTier tier : AgentTier.values()) {
      stubResolver(resolver, LlmProvider.ANTHROPIC, tier, SONNET);
    }
    return new RunCostTracker(pricingRepo, resolver);
  }

  /** Tracker with NO pricing rows at all — every rate lookup returns 0/0. */
  private static RunCostTracker unpricedTracker() {
    ModelPricingRepository pricingRepo = mock(ModelPricingRepository.class);
    ModelResolver resolver = mock(ModelResolver.class);
    lenient()
        .when(pricingRepo.findByIdProviderAndIdModel(anyString(), anyString()))
        .thenReturn(Optional.empty());
    for (AgentTier tier : AgentTier.values()) {
      stubResolver(resolver, LlmProvider.ANTHROPIC, tier, SONNET);
    }
    return new RunCostTracker(pricingRepo, resolver);
  }

  // (a) Two different plans on the same route MUST produce materially different estimates.

  @Test
  void oneTaskLowAndEightTaskHighPlansDifferMateriallyOnTheSameRoute() {
    RunCostTracker tracker = deepseekTracker();
    RunCostTracker.Estimate small =
        tracker.estimateRunTierAware(
            "deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, false);
    RunCostTracker.Estimate large =
        tracker.estimateRunTierAware(
            "deepseek",
            List.of("HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH"),
            PlanStrategy.SINGLE_SHOT,
            1,
            false);

    assertThat(large.highUsd())
        .as("an 8-task HIGH plan must cost far more than a 1-task LOW plan on the same route")
        .isGreaterThan(small.highUsd() * 10.0);
    assertThat(large.lowUsd()).isGreaterThan(small.lowUsd() * 10.0);
    assertThat(large.taskCount()).isEqualTo(8);
    assertThat(small.taskCount()).isEqualTo(1);
  }

  // (b) The same plan must reproduce the same estimate (pure function of its inputs).

  @Test
  void sameNonTrivialPlanReproducesByteIdenticalEstimate() {
    RunCostTracker tracker = anthropicTracker();
    List<String> plan = List.of("LOW", "MEDIUM", "HIGH");
    RunCostTracker.Estimate first =
        tracker.estimateRunTierAware("anthropic", plan, PlanStrategy.MEDIUM, 2, true);
    RunCostTracker.Estimate second =
        tracker.estimateRunTierAware("anthropic", plan, PlanStrategy.MEDIUM, 2, true);
    assertThat(first).isEqualTo(second);
  }

  // (c) Floor — never zero/negative, even with a completely unpriced route or an empty plan.

  @Test
  void unpricedRouteStillProducesFlooredPositiveEstimate() {
    RunCostTracker.Estimate est =
        unpricedTracker()
            .estimateRunTierAware("anthropic", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, false);
    assertThat(est.lowUsd()).as("low estimate is floored, never zero").isGreaterThan(0.0);
    assertThat(est.highUsd())
        .as("high band sits above the floored low")
        .isGreaterThan(est.lowUsd());
    assertThat(est.credits()).isEqualTo(1);
    assertThat(est.hardCapUsd()).as("credit-quantized hard-cap floor").isEqualTo(5.0);
  }

  @Test
  void emptyPlanStillProducesFlooredPositiveEstimate() {
    RunCostTracker.Estimate est =
        deepseekTracker()
            .estimateRunTierAware(
                "deepseek", Collections.emptyList(), PlanStrategy.SINGLE_SHOT, 1, false);
    assertThat(est.lowUsd()).isGreaterThan(0.0);
    assertThat(est.highUsd()).isGreaterThanOrEqualTo(est.lowUsd());
    assertThat(est.credits()).isGreaterThanOrEqualTo(1);
  }

  // (d) Credit formula unchanged: credits = max(1, ceil(buffered / $5)), hardCap = credits x $5,
  //     buffered = midpoint x 1.20.

  @Test
  void creditFormulaIsUnchangedAcrossSmallAndLargePlans() {
    RunCostTracker tracker = anthropicTracker();
    for (RunCostTracker.Estimate est :
        List.of(
            tracker.estimateRunTierAware(
                "anthropic", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, false),
            tracker.estimateRunTierAware(
                "anthropic",
                List.of("HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH"),
                PlanStrategy.LARGE,
                3,
                true))) {
      double buffered = (est.lowUsd() + est.highUsd()) / 2.0 * 1.20;
      int expectedCredits = Math.max(1, (int) Math.ceil(buffered / 5.0));
      assertThat(est.credits()).isEqualTo(expectedCredits);
      assertThat(est.hardCapUsd()).isEqualTo(expectedCredits * 5.0);
    }
    // The large-plan shape must actually exercise the multi-credit branch, or this test proves
    // nothing about proportional charging.
    RunCostTracker.Estimate large =
        tracker.estimateRunTierAware(
            "anthropic",
            List.of("HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH"),
            PlanStrategy.LARGE,
            3,
            true);
    assertThat(large.credits()).isGreaterThan(1);
  }

  // Calibration pins for the observed defect and the conservatism boundary.

  @Test
  void smallStandaloneDeepseekRunNoLongerOvershootsByAnOrderOfMagnitude() {
    // Observed actuals on single-task DeepSeek runs: $0.0100-$0.0129. The old estimator's high
    // band for this shape was ~$0.148 (11-15x). The plan-scaled estimate must land near 3x of the
    // top observed actual while its low band stays a defensible expected cost (>= actual range).
    RunCostTracker.Estimate est =
        deepseekTracker()
            .estimateRunTierAware("deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, false);
    assertThat(est.lowUsd())
        .as("expected cost stays at/above the observed actual band")
        .isGreaterThan(0.010);
    assertThat(est.highUsd())
        .as("high band within ~4x of the $0.0129 top actual, not 11-15x")
        .isLessThan(0.05);
  }

  @Test
  void tailMultiplierIsPlanScaledAndCappedAtThree() {
    RunCostTracker tracker = deepseekTracker();
    RunCostTracker.Estimate small =
        tracker.estimateRunTierAware(
            "deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, false);
    RunCostTracker.Estimate large =
        tracker.estimateRunTierAware(
            "deepseek",
            List.of("HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH", "HIGH"),
            PlanStrategy.SINGLE_SHOT,
            5,
            true);

    // Small standalone 1-LOW plan: 1.6 base + 0.15 x weight(1) = 1.75.
    assertThat(small.highUsd() / small.lowUsd()).isCloseTo(1.75, within(0.001));
    // Large multi-repo project plan saturates at the 3.0 conservatism cap, never beyond it.
    assertThat(large.highUsd() / large.lowUsd()).isCloseTo(3.0, within(0.001));
  }

  private static void stubPricing(
      ModelPricingRepository repo, String provider, String model, double in, double out) {
    ModelPricing mp = new ModelPricing();
    mp.setInputCostPer1m(in);
    mp.setOutputCostPer1m(out);
    lenient().when(repo.findByIdProviderAndIdModel(provider, model)).thenReturn(Optional.of(mp));
  }

  private static void stubResolver(
      ModelResolver resolver, LlmProvider provider, AgentTier tier, String model) {
    ResolvedModel rm =
        ResolvedModel.builder()
            .requestedProvider(provider)
            .actualProvider(provider)
            .model(model)
            .agentTier(tier)
            .fallback(false)
            .build();
    lenient().when(resolver.resolve(anyString(), eq(provider), eq(tier))).thenReturn(rm);
    lenient().when(resolver.resolve(anyString(), eq(provider))).thenReturn(rm);
  }
}
