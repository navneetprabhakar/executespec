package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BenchmarkGenerationServiceDiffCaptureTest {

  @Test
  void preservesARealEmptyDiff() {
    assertThat(
            BenchmarkGenerationService.captureDiffOutput(
                new BenchmarkGenerationService.ShellResult(0, "")))
        .isEmpty();
  }

  @Test
  void failsClosedWhenGitCannotCaptureThePatch() {
    assertThatThrownBy(
            () ->
                BenchmarkGenerationService.captureDiffOutput(
                    new BenchmarkGenerationService.ShellResult(
                        128, "fatal: detected dubious ownership in repository")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("patch capture failed")
        .hasMessageContaining("dubious ownership");
  }

  @Test
  void acceptsOnlyCommitObjectIdsOrHeadFallback() {
    assertThat(BenchmarkGenerationService.validatedDiffTarget(null)).isEqualTo("HEAD");
    assertThat(BenchmarkGenerationService.validatedDiffTarget("abc1234")).isEqualTo("abc1234");
    assertThatThrownBy(
            () -> BenchmarkGenerationService.validatedDiffTarget("HEAD; curl attacker.invalid"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid benchmark base commit");
  }
}
