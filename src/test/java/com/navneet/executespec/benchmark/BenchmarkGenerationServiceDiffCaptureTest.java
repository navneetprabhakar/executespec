package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BenchmarkGenerationServiceDiffCaptureTest {

  @Test
  void installsExactProtectedSafeDirectoryBeforeLitePatchCapture() {
    String command =
        BenchmarkGenerationService.captureDiffCommand(
            "bench-gen-deadbeef", "abcdef1234567890", "/testbed", false);

    assertThat(command)
        .contains("cd /testbed; git config --global --replace-all safe.directory /testbed")
        .contains("; git add -N --all >/dev/null; git diff abcdef1234567890")
        .doesNotContain("git -c safe.directory");
    assertThat(command.indexOf("git config --global")).isLessThan(command.indexOf("git add -N"));
    assertThat(command.indexOf("git add -N"))
        .isLessThan(command.indexOf("git diff abcdef1234567890"));
  }

  @Test
  void installsExactProtectedSafeDirectoryForProWorkdir() {
    assertThat(
            BenchmarkGenerationService.captureDiffCommand(
                "bench-gen-0123abcd", "abcdef1", "/app", true))
        .contains("cd /app; git config --global --replace-all safe.directory /app")
        .contains("; git add -N --all >/dev/null; git diff abcdef1");
  }

  @Test
  void rejectsShellControlledCaptureInputs() {
    assertThatThrownBy(
            () ->
                BenchmarkGenerationService.captureDiffCommand(
                    "bench-gen-deadbeef;curl attacker.invalid", "abcdef1", "/testbed", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("container name");
    assertThatThrownBy(
            () ->
                BenchmarkGenerationService.captureDiffCommand(
                    "bench-gen-deadbeef", "abcdef1", "/testbed;curl attacker.invalid", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workdir");
  }

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
