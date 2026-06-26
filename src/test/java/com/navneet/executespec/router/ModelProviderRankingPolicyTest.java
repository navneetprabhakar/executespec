package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelProviderRankingPolicyTest {

  private final ModelProviderRankingPolicy policy = new ModelProviderRankingPolicy();

  @Test
  void filtersBelowThresholdCandidateEvenWhenCheapestAndFastest() {
    ModelProviderScore cheapButWeak = score("openai", "mini", 79, 80, 0.10, 100, "BUG_FIX");
    ModelProviderScore expensiveEnough =
        score("anthropic", "sonnet", 82, 80, 5.00, 2_000, "BUG_FIX");

    ModelProviderRankingDecision decision = policy.rank(List.of(cheapButWeak, expensiveEnough));

    assertThat(decision.selected()).contains(expensiveEnough);
    assertThat(decision.rejectedBelowThreshold()).containsExactly(cheapButWeak);
    assertThat(decision.reasons())
        .anySatisfy(reason -> assertThat(reason).contains("rejected below quality threshold"));
  }

  @Test
  void ranksByQualityBeforeCost() {
    ModelProviderScore cheaperLowerQuality =
        score("openai", "mini", 87, 80, 0.10, 400, "PROJECT_PATCH");
    ModelProviderScore costlyHigherQuality =
        score("anthropic", "sonnet", 91, 80, 4.00, 1_600, "PROJECT_PATCH");

    ModelProviderRankingDecision decision =
        policy.rank(List.of(cheaperLowerQuality, costlyHigherQuality));

    assertThat(decision.selected()).contains(costlyHigherQuality);
    assertThat(decision.eligible()).containsExactly(costlyHigherQuality, cheaperLowerQuality);
  }

  @Test
  void ranksByCostBeforeLatencyWhenQualityIsEqual() {
    ModelProviderScore fastExpensive = score("anthropic", "sonnet", 90, 80, 5.00, 200, "REFACTOR");
    ModelProviderScore slowCheap = score("deepseek", "v4", 90, 80, 0.30, 1_500, "REFACTOR");

    ModelProviderRankingDecision decision = policy.rank(List.of(fastExpensive, slowCheap));

    assertThat(decision.selected()).contains(slowCheap);
    assertThat(decision.eligible()).containsExactly(slowCheap, fastExpensive);
  }

  @Test
  void ranksByLatencyAfterQualityAndCost() {
    ModelProviderScore slow = score("anthropic", "sonnet", 90, 80, 1.00, 2_000, "GREENFIELD_BUILD");
    ModelProviderScore fast = score("openai", "gpt", 90, 80, 1.00, 700, "GREENFIELD_BUILD");

    ModelProviderRankingDecision decision = policy.rank(List.of(slow, fast));

    assertThat(decision.selected()).contains(fast);
    assertThat(decision.eligible()).containsExactly(fast, slow);
  }

  @Test
  void returnsNoSelectionWhenNoCandidateMeetsQualityThreshold() {
    ModelProviderScore weakA = score("openai", "mini", 70, 85, 0.10, 300, "BENCHMARK_PATCH");
    ModelProviderScore weakB = score("gemini", "flash", 84, 85, 0.20, 200, "BENCHMARK_PATCH");

    ModelProviderRankingDecision decision = policy.rank(List.of(weakA, weakB));

    assertThat(decision.hasSelection()).isFalse();
    assertThat(decision.selected()).isEmpty();
    assertThat(decision.eligible()).isEmpty();
    assertThat(decision.rejectedBelowThreshold()).containsExactly(weakB, weakA);
    assertThat(decision.reasons().getFirst()).contains("rejected below required quality threshold");
  }

  @Test
  void emptyCandidateListReturnsExplainableNoSelection() {
    ModelProviderRankingDecision decision = policy.rank(List.of());

    assertThat(decision.hasSelection()).isFalse();
    assertThat(decision.reasons()).containsExactly("no candidates supplied");
  }

  private static ModelProviderScore score(
      String provider,
      String model,
      double quality,
      double threshold,
      double cost,
      long latency,
      String category) {
    return new ModelProviderScore(
        provider, model, category, quality, cost, latency, latency * 2, threshold, List.of());
  }
}
