package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelProviderScoreTest {

  @Test
  void compatibilityConstructorsNeverInferQualityProofFromReasonText() {
    ModelProviderScore legacy =
        new ModelProviderScore(
            "deepseek",
            "deepseek-v4-flash",
            "BUG_FIX",
            98,
            1,
            100,
            200,
            70,
            List.of("persisted validation pass rate 98% from 20 tasks"));
    ModelProviderScore benchmarkCompat =
        new ModelProviderScore(
            "deepseek",
            "deepseek-v4-flash",
            "BUG_FIX",
            98,
            1,
            100,
            200,
            70,
            true,
            List.of("quality 98 from persisted validation"));

    assertThat(legacy.observedQualityEvidenceAvailable()).isFalse();
    assertThat(benchmarkCompat.observedQualityEvidenceAvailable()).isFalse();
    assertThat(benchmarkCompat.benchmarkQualityPriorAvailable()).isTrue();
  }

  @Test
  void nanQualityScoreCannotPassTheThreshold() {
    // RR-397: a NaN quality score must fail the threshold, never slip past it. It is coerced to the
    // worst quality (0.0) so the below-threshold check rejects it deterministically.
    ModelProviderScore score =
        new ModelProviderScore(
            "openai", "gpt-5.4", "BUG_FIX", Double.NaN, 1, 100, 200, 70, List.of("nan quality"));

    assertThat(score.qualityScore()).isZero();
    assertThat(score.meetsQualityThreshold()).isFalse();
  }

  @Test
  void nanThresholdBecomesStrictestBarAndRejectsAStrongCandidate() {
    // RR-397: a NaN required threshold must not make every score eligible. It is coerced to 100.0
    // (the strictest bar), so even a 90-quality candidate is rejected rather than admitted.
    ModelProviderScore score =
        new ModelProviderScore(
            "anthropic",
            "sonnet",
            "BUG_FIX",
            90,
            1,
            100,
            200,
            Double.NaN,
            List.of("nan threshold"));

    assertThat(score.requiredQualityThreshold()).isEqualTo(100.0);
    assertThat(score.meetsQualityThreshold()).isFalse();
  }

  @Test
  void nonFiniteCostIsCoercedToMaximumSoItNeverRanksCheapest() {
    // RR-397: a NaN/infinite cost must not be read as "cheapest". It becomes Double.MAX_VALUE so it
    // sorts last within a quality tie band.
    ModelProviderScore score =
        new ModelProviderScore(
            "openai", "gpt-5.4", "BUG_FIX", 90, Double.NaN, 100, 200, 70, List.of("nan cost"));

    assertThat(score.costPerMillionTokens()).isEqualTo(Double.MAX_VALUE);
    assertThat(score.meetsQualityThreshold()).isTrue();
  }
}
