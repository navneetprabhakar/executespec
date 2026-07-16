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
}
