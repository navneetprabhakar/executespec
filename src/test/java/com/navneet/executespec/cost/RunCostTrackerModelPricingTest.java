package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 2026-07-17 — the pre-submit Launch estimate must change with the selected model. Pins that {@link
 * RunCostTracker#estimateTokenCostUsd} prices the SAME token volume differently per model, and that
 * an unpriced model falls back to the provider's workhorse-tier rate instead of the old silent
 * {@code {0,0}} (which read as "$0 / estimate never changes").
 */
class RunCostTrackerModelPricingTest {

  private RunCostTracker tracker;

  @BeforeEach
  void setUp() {
    ModelPricingRepository pricingRepo = mock(ModelPricingRepository.class);
    ModelResolver resolver = mock(ModelResolver.class);

    stubPricing(pricingRepo, "openai", "gpt-5.4", 2.5, 15.0);
    stubPricing(pricingRepo, "openai", "gpt-5.4-mini", 0.75, 4.5);
    stubPricing(pricingRepo, "anthropic", "claude-sonnet-5", 2.0, 10.0);
    // openai workhorse default = gpt-5.4 (used for the unpriced-model fallback).
    stubResolver(resolver, LlmProvider.OPENAI, AgentTier.WORKHORSE, "gpt-5.4");

    tracker = new RunCostTracker(pricingRepo, resolver);
  }

  @Test
  void estimateChangesWithSelectedModel() {
    long inTok = 1_000_000;
    long outTok = 100_000;

    double full = tracker.estimateTokenCostUsd("openai", "gpt-5.4", inTok, outTok);
    double mini = tracker.estimateTokenCostUsd("openai", "gpt-5.4-mini", inTok, outTok);
    double sonnet = tracker.estimateTokenCostUsd("anthropic", "claude-sonnet-5", inTok, outTok);

    // gpt-5.4: 1M*2.5 + 0.1M*15 = 2.5 + 1.5 = 4.0
    assertThat(full).isCloseTo(4.0, org.assertj.core.api.Assertions.within(1e-6));
    // gpt-5.4-mini: 1M*0.75 + 0.1M*4.5 = 0.75 + 0.45 = 1.2
    assertThat(mini).isCloseTo(1.2, org.assertj.core.api.Assertions.within(1e-6));
    // Selecting the cheaper model must lower the estimate — the whole point of the fix.
    assertThat(mini).isLessThan(full);
    // A different provider's model is distinct again.
    assertThat(sonnet).isCloseTo(3.0, org.assertj.core.api.Assertions.within(1e-6));
    assertThat(sonnet).isNotEqualTo(full);
  }

  @Test
  void unpricedModelFallsBackToProviderWorkhorseRateNotZero() {
    long inTok = 1_000_000;
    long outTok = 100_000;

    double unpriced = tracker.estimateTokenCostUsd("openai", "gpt-9-does-not-exist", inTok, outTok);

    // Falls back to the openai workhorse default (gpt-5.4 = 2.5/15) → 4.0, NOT 0.
    assertThat(unpriced).isCloseTo(4.0, org.assertj.core.api.Assertions.within(1e-6));
    assertThat(unpriced).isGreaterThan(0.0);
  }

  @Test
  void blankModelUsesProviderDefault() {
    double blank = tracker.estimateTokenCostUsd("openai", "", 1_000_000, 0);
    double nul = tracker.estimateTokenCostUsd("openai", null, 1_000_000, 0);
    assertThat(blank).isCloseTo(2.5, org.assertj.core.api.Assertions.within(1e-6));
    assertThat(nul).isEqualTo(blank);
  }

  @Test
  void unknownProviderYieldsZeroRatherThanThrowing() {
    assertThat(tracker.estimateTokenCostUsd("not-a-provider", "x", 1_000_000, 0)).isZero();
    assertThat(tracker.estimateTokenCostUsd(null, "x", 1_000_000, 0)).isZero();
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
  }
}
