package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the diff-exclusion variants (B.2 finding #4): the Pro list must never contain a rule that
 * the 2026-07-04 gold-patch audit showed stripping real fixes (32/731 instances), while test-file
 * and agent-noise rules stay (Scale moves test changes to test_patch, so those are provably safe).
 */
class BenchmarkDiffExcludesTest {

  @Test
  void proExcludesDropTheGoldHittingRules() {
    String pro = BenchmarkGenerationService.diffExcludes(true);
    assertThat(pro).doesNotContain("testing/**");
    assertThat(pro).doesNotContain("README.md");
    assertThat(pro).doesNotContain("Dockerfile");
    assertThat(pro).doesNotContain(".github");
    assertThat(pro).doesNotContain("requirements*");
    assertThat(pro).doesNotContain("pyproject.toml");
    assertThat(pro).doesNotContain("setup.py");
    // safe rules retained
    assertThat(pro).contains(".agent-context");
    assertThat(pro).contains("**/tests/**");
    assertThat(pro).contains("**/test_*.py");
    assertThat(pro).contains("conftest.py");
    assertThat(pro).contains("repro");
  }

  @Test
  void classicExcludesKeepTheHistoricalList() {
    String classic = BenchmarkGenerationService.diffExcludes(false);
    assertThat(classic).contains("testing/**");
    assertThat(classic).contains("README.md");
    assertThat(classic).contains("**/tests/**");
  }
}
