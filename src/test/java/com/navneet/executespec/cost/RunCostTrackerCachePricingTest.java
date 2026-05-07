package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 2026-05-06 — pins the cache-pricing single-source-of-truth refactor.
 *
 * <p>Before: {@code RunCostTracker.priceCall} had hardcoded per-provider cache-read multipliers
 * (Anthropic 0.10, OpenAI 0.50, Gemini 0.25) that ignored the YAML {@code cached-input-per-1m}
 * field entirely. After: YAML's {@code cached-input-per-1m} is the only knob.
 *
 * <p>Tests use reflection to call the private {@code priceCall} directly so we can pin the math
 * without spinning up the full Spring context.
 */
class RunCostTrackerCachePricingTest {

  private ModelPricingRepository pricingRepo;
  private RunCostTracker tracker;
  private Method priceCall;

  @BeforeEach
  void setUp() throws Exception {
    pricingRepo = mock(ModelPricingRepository.class);
    ModelResolver resolver = mock(ModelResolver.class);
    tracker = new RunCostTracker(pricingRepo, resolver);
    priceCall =
        RunCostTracker.class.getDeclaredMethod(
            "priceCall", String.class, String.class, int.class, int.class, int.class, int.class);
    priceCall.setAccessible(true);
  }

  private double price(
      String provider, String model, int inTok, int outTok, int cacheWrite, int cacheRead)
      throws Exception {
    return (double)
        priceCall.invoke(tracker, provider, model, inTok, outTok, cacheWrite, cacheRead);
  }

  private void seedPricing(
      String provider, String model, double inRate, double cachedRate, double outRate) {
    ModelPricing p = new ModelPricing();
    p.setInputCostPer1m(inRate);
    p.setCachedInputCostPer1m(cachedRate);
    p.setOutputCostPer1m(outRate);
    lenient()
        .when(pricingRepo.findByIdProviderAndIdModel(provider, model))
        .thenReturn(Optional.of(p));
  }

  // ── Anthropic — input is uncached-only, cache write billed at 1.25× input ─

  @Test
  void anthropic_noCacheActivity_costMatchesYaml() throws Exception {
    seedPricing("anthropic", "claude-sonnet-4-6", 3.00, 0.30, 15.00);
    // 1M in / 1M out → $3 input + $15 output = $18
    double cost = price("anthropic", "claude-sonnet-4-6", 1_000_000, 1_000_000, 0, 0);
    assertThat(cost).isEqualTo(18.0, within(0.001));
  }

  @Test
  void anthropic_cacheRead_billedAtYamlCachedRate() throws Exception {
    seedPricing("anthropic", "claude-sonnet-4-6", 3.00, 0.30, 15.00);
    // Anthropic: input_tokens IS uncached-only. So 1M cacheRead is billed at $0.30, not
    // overlapping with input_tokens.
    // 1M in (uncached) × $3 + 1M cacheRead × $0.30 + 0 out = $3 + $0.30 = $3.30
    double cost = price("anthropic", "claude-sonnet-4-6", 1_000_000, 0, 0, 1_000_000);
    assertThat(cost).isEqualTo(3.30, within(0.001));
  }

  @Test
  void anthropic_cacheWrite_billedAt125xInput() throws Exception {
    seedPricing("anthropic", "claude-sonnet-4-6", 3.00, 0.30, 15.00);
    // 1M cacheWrite × $3 × 1.25 = $3.75
    double cost = price("anthropic", "claude-sonnet-4-6", 0, 0, 1_000_000, 0);
    assertThat(cost).isEqualTo(3.75, within(0.001));
  }

  // ── OpenAI — prompt_tokens INCLUDES cached_tokens, so subtract. Cache rate is now $1.25. ─

  @Test
  void openai_cacheRead_billedAtYaml1_25_perM() throws Exception {
    // YAML post-2026-05-06: cached-input-per-1m = $1.25 (50% of $2.50 input).
    seedPricing("openai", "gpt-5.4", 2.50, 1.25, 15.00);
    // 1M prompt_tokens (includes 500K cached). Uncached portion = 500K × $2.50 = $1.25.
    // Cache portion = 500K × $1.25 = $0.625.
    // Total = $1.875.
    double cost = price("openai", "gpt-5.4", 1_000_000, 0, 0, 500_000);
    assertThat(cost).isEqualTo(1.875, within(0.001));
  }

  @Test
  void openai_noCacheActivity_subtractZero_billsAllAtInputRate() throws Exception {
    seedPricing("openai", "gpt-5.4", 2.50, 1.25, 15.00);
    // 1M in × $2.50 + 500K out × $15 = $2.50 + $7.50 = $10.00
    double cost = price("openai", "gpt-5.4", 1_000_000, 500_000, 0, 0);
    assertThat(cost).isEqualTo(10.0, within(0.001));
  }

  // ── Gemini — same shape as OpenAI. Cache rate $0.875 (25% of $3.50 input). ─

  @Test
  void gemini_cacheRead_billedAtYaml0_875_perM() throws Exception {
    // YAML post-2026-05-06: cached-input-per-1m = $0.875 (25% of $3.50 input).
    seedPricing("gemini", "gemini-3-pro-preview", 3.50, 0.875, 21.00);
    // 1M promptTokenCount (includes 200K cachedContentTokenCount).
    // Uncached portion = 800K × $3.50 = $2.80.
    // Cache portion = 200K × $0.875 = $0.175.
    // Total = $2.975.
    double cost = price("gemini", "gemini-3-pro-preview", 1_000_000, 0, 0, 200_000);
    assertThat(cost).isEqualTo(2.975, within(0.001));
  }

  @Test
  void gemini_flash_cacheRate_0_125_perM() throws Exception {
    // YAML: gemini-3-flash-preview input $0.50, cached $0.125.
    seedPricing("gemini", "gemini-3-flash-preview", 0.50, 0.125, 3.00);
    // 100K total in, 50K cached → 50K uncached × $0.50/M + 50K cached × $0.125/M
    // = $0.025 + $0.00625 = $0.03125
    double cost = price("gemini", "gemini-3-flash-preview", 100_000, 0, 0, 50_000);
    assertThat(cost).isEqualTo(0.03125, within(0.0001));
  }

  // ── YAML cached rate missing — defensive fallback to 10% of input ─

  @Test
  void cachedRateMissing_fallsBackToTenPercentOfInput() throws Exception {
    // Pretend the YAML row is from before the 2026-05-06 cache-rate seeding —
    // cachedInputCostPer1m is null. Defensive code falls back to inRate * 0.10
    // (the Anthropic-shape default) so the cost path doesn't crash with NPE.
    ModelPricing p = new ModelPricing();
    p.setInputCostPer1m(2.50);
    p.setCachedInputCostPer1m(null);
    p.setOutputCostPer1m(15.0);
    when(pricingRepo.findByIdProviderAndIdModel("anthropic", "claude-legacy"))
        .thenReturn(Optional.of(p));

    // Anthropic semantics: input_tokens is uncached-only, so 1M in × $2.50 + 1M cached × $0.25
    // (= $2.50 × 0.10 fallback) = $2.75.
    double cost = price("anthropic", "claude-legacy", 1_000_000, 0, 0, 1_000_000);
    assertThat(cost).isEqualTo(2.5 + 0.25, within(0.001));
  }

  // ── No pricing row at all → 0 ─

  @Test
  void noPricingRow_returnsZero() throws Exception {
    when(pricingRepo.findByIdProviderAndIdModel("anthropic", "no-such-model"))
        .thenReturn(Optional.empty());
    assertThat(price("anthropic", "no-such-model", 1_000_000, 1_000_000, 0, 0)).isZero();
  }
}
