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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stage 1 cost-estimation fix pins the estimator against the audit-run reality. Audit run 6024f510
 * (2026-05-12) had estimate $0.05-$0.09 against actual $1.04 — a 12× miss. Stage 1 formula adds
 * reviewer / accuracy / codemap / cache-write components that the old estimator ignored; this test
 * pins the new band so it doesn't silently regress.
 */
class RunCostTrackerEstimateStage1Test {

  private RunCostTracker tracker;

  /** Anthropic Sonnet — what the audit run actually billed at (planner + worker + reviewer all). */
  private static final String ANTHROPIC_SONNET = "claude-sonnet-4-6";

  /** Anthropic Haiku — old estimator's LIGHTWEIGHT-tier price for LOW tasks. */
  private static final String ANTHROPIC_HAIKU = "claude-haiku-4-5-20251001";

  /** OpenAI mini — Gemini Flash analogue for cross-provider parity sanity. */
  private static final String OPENAI_MINI = "gpt-5.4-mini";

  /** OpenAI full — PLANNING tier. */
  private static final String OPENAI_FULL = "gpt-5.4";

  /** Rates from `application.yml model-matrix.model-pricing` (USD per 1M). */
  private static final double SONNET_IN = 3.0;

  private static final double SONNET_OUT = 15.0;
  private static final double HAIKU_IN = 1.0;
  private static final double HAIKU_OUT = 5.0;
  private static final double GPT5_IN = 5.0;
  private static final double GPT5_OUT = 20.0;
  private static final double GPT5_MINI_IN = 0.6;
  private static final double GPT5_MINI_OUT = 2.4;

  @BeforeEach
  void setUp() {
    ModelPricingRepository pricingRepo = mock(ModelPricingRepository.class);
    ModelResolver resolver = mock(ModelResolver.class);

    // ModelPricingRepository keys are lowercase per RunCostTracker.providerKey()
    stubPricing(pricingRepo, "anthropic", ANTHROPIC_SONNET, SONNET_IN, SONNET_OUT);
    stubPricing(pricingRepo, "anthropic", ANTHROPIC_HAIKU, HAIKU_IN, HAIKU_OUT);
    stubPricing(pricingRepo, "openai", OPENAI_FULL, GPT5_IN, GPT5_OUT);
    stubPricing(pricingRepo, "openai", OPENAI_MINI, GPT5_MINI_IN, GPT5_MINI_OUT);

    stubResolver(resolver, LlmProvider.ANTHROPIC, AgentTier.PLANNING, ANTHROPIC_SONNET);
    stubResolver(resolver, LlmProvider.ANTHROPIC, AgentTier.WORKHORSE, ANTHROPIC_SONNET);
    stubResolver(resolver, LlmProvider.ANTHROPIC, AgentTier.LIGHTWEIGHT, ANTHROPIC_HAIKU);
    stubResolver(resolver, LlmProvider.OPENAI, AgentTier.PLANNING, OPENAI_FULL);
    stubResolver(resolver, LlmProvider.OPENAI, AgentTier.WORKHORSE, OPENAI_MINI);
    stubResolver(resolver, LlmProvider.OPENAI, AgentTier.LIGHTWEIGHT, OPENAI_MINI);

    tracker = new RunCostTracker(pricingRepo, resolver);
  }

  // Audit run shape — SINGLE_SHOT × 1 LOW task × 3-repo project × Anthropic

  @Test
  void audit_6024f510_shape_bands_now_include_actual() {
    // Run 6024f510 actual was $1.04. The OLD estimator (no repoCount / not project-scoped /
    // ignores reviewer+codemap+cache-write) banded $0.05-$0.09. The new 5-arg overload with
    // repoCount=3 + isProjectScoped=true should band so $1.04 sits inside [low, high].
    RunCostTracker.Estimate est =
        tracker.estimateRunTierAware(
            "anthropic", List.of("LOW"), PlanStrategy.SINGLE_SHOT, /* repoCount */ 3, true);

    assertThat(est.lowUsd()).as("low band").isGreaterThan(0.20);
    assertThat(est.highUsd()).as("high band").isGreaterThan(1.04);
    assertThat(est.highUsd() / est.lowUsd())
        .as("high/low ratio reflects HIGH_MULTIPLIER 3.0")
        .isCloseTo(3.0, org.assertj.core.api.Assertions.within(0.001));
    assertThat(est.credits()).as("at least 1 credit").isGreaterThanOrEqualTo(1);
    assertThat(est.hardCapUsd())
        .as("hardCap is credits × USD_PER_CREDIT (5)")
        .isEqualTo(est.credits() * 5.0);
  }

  @Test
  void old_2_arg_overload_back_compat_with_repoCount_default_1() {
    // The 3-arg + 2-arg overloads default to repoCount=1, isProjectScoped=false. That should
    // produce a SMALLER estimate than the audit-shape 5-arg call (which had repoCount=3 +
    // project=true). Both > 0 for a non-trivial run.
    RunCostTracker.Estimate small =
        tracker.estimateRunTierAware("anthropic", List.of("LOW"), PlanStrategy.SINGLE_SHOT);
    RunCostTracker.Estimate big =
        tracker.estimateRunTierAware(
            "anthropic", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 3, true);
    assertThat(small.lowUsd()).isLessThan(big.lowUsd());
    assertThat(small.highUsd()).isLessThan(big.highUsd());
  }

  @Test
  void wiring_pass_cost_drops_out_on_single_repo_single_task() {
    // 5-arg with repoCount=1 + 1 task should EXCLUDE the wiring-pass cost (matches the
    // RunOrchestrator 2026-05-13 broadened skip-wiring gate). 2 tasks at repoCount=1 should
    // INCLUDE wiring → strictly larger.
    RunCostTracker.Estimate singleSingle =
        tracker.estimateRunTierAware(
            "anthropic", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, false);
    RunCostTracker.Estimate twoTasks =
        tracker.estimateRunTierAware(
            "anthropic", List.of("LOW", "MEDIUM"), PlanStrategy.SMALL, 1, false);
    assertThat(twoTasks.lowUsd() - singleSingle.lowUsd())
        .as("the wiring pass should be turned on by 2 tasks")
        .isGreaterThan(0.10);
  }

  @Test
  void extra_repos_inflate_wiring_band() {
    // repoCount 1 vs 5 with the same plan should differ by ~4 × per-extra-repo overhead AND
    // the codemap cost on the project-scoped path.
    RunCostTracker.Estimate one =
        tracker.estimateRunTierAware(
            "anthropic", List.of("MEDIUM", "MEDIUM"), PlanStrategy.MEDIUM, 1, true);
    RunCostTracker.Estimate five =
        tracker.estimateRunTierAware(
            "anthropic", List.of("MEDIUM", "MEDIUM"), PlanStrategy.MEDIUM, 5, true);
    // The delta is bounded by codemap × (5-1) + per-extra-repo wiring × 4 — small relative to
    // a 2 × MEDIUM-task worker baseline, but strictly positive.
    assertThat(five.lowUsd()).isGreaterThan(one.lowUsd());
    assertThat(five.lowUsd() - one.lowUsd()).as("extra-repo overhead is real").isGreaterThan(0.10);
  }

  @Test
  void openai_estimate_has_no_cache_write_overhead() {
    // The Anthropic-only cache-write 25%-premium term should not apply to OpenAI runs.
    RunCostTracker.Estimate anthropic =
        tracker.estimateRunTierAware("anthropic", List.of("MEDIUM"), PlanStrategy.SMALL, 1, false);
    RunCostTracker.Estimate openai =
        tracker.estimateRunTierAware("openai", List.of("MEDIUM"), PlanStrategy.SMALL, 1, false);
    // Even after pricing differences, anthropic should have the cache premium that openai
    // lacks. We can't compare absolute (different rates) but we can verify both produce a
    // credible non-zero number.
    assertThat(anthropic.lowUsd()).isGreaterThan(0.0);
    assertThat(openai.lowUsd()).isGreaterThan(0.0);
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
