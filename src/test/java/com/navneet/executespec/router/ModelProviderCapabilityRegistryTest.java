package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelProviderCapabilityRegistryTest {

  private final ModelProviderCapabilityRegistry registry = new ModelProviderCapabilityRegistry();
  private final ModelProviderRankingPolicy rankingPolicy = new ModelProviderRankingPolicy();

  @Test
  void combinesBenchmarkLocalValidationAndToolReliabilityIntoQualityScore() {
    ModelProviderScore score =
        registry.score(
            signal("anthropic", "sonnet")
                .benchmarkQualityScore(90.0)
                .localSuccessRateScore(80.0)
                .validationPassRateScore(70.0)
                .codeReviewScore(85.0)
                .toolReliabilityScore(95.0)
                .build());

    assertThat(score.qualityScore()).isEqualTo(84.25);
    assertThat(score.costPerMillionTokens()).isEqualTo(9.0);
    assertThat(score.reasons()).anySatisfy(reason -> assertThat(reason).contains("benchmark"));
    assertThat(score.reasons()).anySatisfy(reason -> assertThat(reason).contains("local_success"));
  }

  @Test
  void unavailableProviderIsForcedBelowThresholdWithReason() {
    ModelProviderScore score =
        registry.score(
            signal("openai", "gpt-5.4")
                .benchmarkQualityScore(99.0)
                .providerAvailable(false)
                .build());

    assertThat(score.qualityScore()).isZero();
    assertThat(score.meetsQualityThreshold()).isFalse();
    assertThat(score.reasons())
        .contains("provider unavailable; candidate is forced below threshold");
  }

  @Test
  void fallsBackSafelyWhenBenchmarkAndLocalSignalsAreMissing() {
    ModelProviderScore score =
        registry.score(
            signal("google", "gemini-flash")
                .fallbackQualityScore(72.0)
                .recentFailurePenalty(5.0)
                .build());

    assertThat(score.qualityScore()).isEqualTo(67.0);
    assertThat(score.meetsQualityThreshold()).isFalse();
    assertThat(score.reasons()).anySatisfy(reason -> assertThat(reason).contains("fallback score"));
    assertThat(score.reasons())
        .anySatisfy(reason -> assertThat(reason).contains("recent failure penalty"));
  }

  @Test
  void feedsQualityFirstRankingWithoutLettingCheapWeakModelWin() {
    ModelProviderScore cheapWeak =
        registry.score(
            signal("anthropic", "haiku")
                .benchmarkQualityScore(68.0)
                .inputCostPerMillionTokens(0.1)
                .outputCostPerMillionTokens(0.2)
                .latencyP50Ms(300)
                .latencyP95Ms(600)
                .build());
    ModelProviderScore expensiveStrong =
        registry.score(
            signal("anthropic", "opus")
                .benchmarkQualityScore(91.0)
                .inputCostPerMillionTokens(10.0)
                .outputCostPerMillionTokens(20.0)
                .latencyP50Ms(2_000)
                .latencyP95Ms(4_000)
                .build());

    ModelProviderRankingDecision decision = rankingPolicy.rank(List.of(cheapWeak, expensiveStrong));

    assertThat(decision.selected()).contains(expensiveStrong);
    assertThat(decision.rejectedBelowThreshold()).containsExactly(cheapWeak);
  }

  @Test
  void fallbackOrderAppliesSmallTieBreakerPenaltyButDoesNotDominateQuality() {
    ModelProviderScore first =
        registry.score(
            signal("anthropic", "sonnet").benchmarkQualityScore(85.0).fallbackOrder(0).build());
    ModelProviderScore later =
        registry.score(
            signal("openai", "gpt-5.4").benchmarkQualityScore(86.0).fallbackOrder(4).build());

    assertThat(first.qualityScore()).isEqualTo(85.0);
    assertThat(later.qualityScore()).isEqualTo(85.0);
  }

  private static ModelProviderCapabilitySignal.ModelProviderCapabilitySignalBuilder signal(
      String provider, String model) {
    return ModelProviderCapabilitySignal.builder()
        .provider(provider)
        .model(model)
        .taskCategory("BUG_FIX")
        .inputCostPerMillionTokens(3.0)
        .outputCostPerMillionTokens(6.0)
        .latencyP50Ms(1_000)
        .latencyP95Ms(2_000)
        .contextWindowTokens(200_000)
        .requiredQualityThreshold(70.0)
        .reasons(List.of("test signal"));
  }
}
