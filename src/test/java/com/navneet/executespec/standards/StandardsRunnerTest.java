package com.navneet.executespec.standards;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.standards.enforcement.ast.AstCheckEnforcer;
import com.navneet.executespec.standards.enforcement.filepattern.FilePatternEnforcer;
import com.navneet.executespec.standards.enforcement.lint.LintAdapter;
import com.navneet.executespec.standards.enforcement.lint.LintResult;
import com.navneet.executespec.standards.enforcement.lint.LintViolation;
import com.navneet.executespec.standards.model.Standard;

import org.junit.jupiter.api.Test;

class StandardsRunnerTest {

  /** Always-success lint adapter that returns the canned violations. */
  static class StubLintAdapter implements LintAdapter {
    final String tool;
    final List<LintViolation> violations;
    volatile String observedRunId;

    StubLintAdapter(String tool, List<LintViolation> violations) {
      this.tool = tool;
      this.violations = violations;
    }

    @Override
    public String tool() {
      return tool;
    }

    @Override
    public LintResult run(String runId, Path repoRoot, Path config, List<Path> changed) {
      this.observedRunId = runId;
      return new LintResult(tool, LintResult.Status.SUCCESS, violations, null);
    }
  }

  @Test
  void emptyEffective_emptyReport() {
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    StandardsRunner.RunReport r =
        runner.run("run-1", List.of(), Path.of("."), List.of(), List.of());
    assertThat(r.violations()).isEmpty();
    assertThat(r.lintResults()).isEmpty();
  }

  @Test
  void filePatternStandard_aggregatesViolations() {
    Standard s =
        new Standard(
            "no-skip-migrations",
            null,
            Standard.Severity.BLOCKING,
            "no SKIP migrations",
            new Standard.FilePatternEnforcement(List.of("**/V*__SKIP*.sql"), List.of()),
            null,
            List.of(),
            false);
    EffectiveStandardsResolver.Effective e = effective(s);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());

    StandardsRunner.RunReport r =
        runner.run(
            "run-1",
            List.of(e),
            Path.of("."),
            List.of("db/V42__SKIP_x.sql", "db/V43__legit.sql"),
            List.of());
    assertThat(r.violations()).hasSize(1);
    assertThat(r.violations().get(0).standardId()).isEqualTo("no-skip-migrations");
    assertThat(r.violations().get(0).enforcementType()).isEqualTo("FILE_PATTERN");
    assertThat(r.hasBlocking()).isTrue();
    assertThat(r.countBySeverity(Standard.Severity.BLOCKING)).isEqualTo(1);
  }

  @Test
  void lintStandard_routesToCorrectAdapter() {
    Standard s =
        new Standard(
            "eslint-rules",
            null,
            Standard.Severity.WARNING,
            "use eslint",
            new Standard.LintEnforcement("eslint", null),
            null,
            List.of(),
            false);
    StubLintAdapter eslint =
        new StubLintAdapter(
            "eslint",
            List.of(new LintViolation("/repo/foo.js", 5, 3, "no-unused-vars", "x is unused")));
    StubLintAdapter detekt = new StubLintAdapter("detekt", List.of()); // not invoked

    StandardsRunner runner =
        new StandardsRunner(
            new FilePatternEnforcer(), new AstCheckEnforcer(), List.of(eslint, detekt));
    StandardsRunner.RunReport r =
        runner.run("run-1", List.of(effective(s)), Path.of("."), List.of(), List.of());

    assertThat(r.violations()).hasSize(1);
    assertThat(r.violations().get(0).enforcementType()).isEqualTo("LINT");
    assertThat(r.lintResults()).containsKey("eslint-rules");
    // The run id is threaded into the adapter — the scope a sandbox needs to contain the linter.
    assertThat(eslint.observedRunId).isEqualTo("run-1");
  }

  @Test
  void lintStandard_unknownToolIsReportedAsUnavailable() {
    Standard s =
        new Standard(
            "ruff-rules",
            null,
            Standard.Severity.WARNING,
            "use ruff",
            new Standard.LintEnforcement("ruff", null),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(
            new FilePatternEnforcer(),
            new AstCheckEnforcer(),
            List.of(new StubLintAdapter("eslint", List.of())));
    StandardsRunner.RunReport r =
        runner.run("run-1", List.of(effective(s)), Path.of("."), List.of(), List.of());
    assertThat(r.violations()).isEmpty();
    assertThat(r.lintResults().get("ruff-rules").status()).isEqualTo(LintResult.Status.UNAVAILABLE);
  }

  @Test
  void llmReviewStandards_skippedHere_handledByPromptComposer() {
    Standard s =
        new Standard(
            "no-pii-in-logs",
            null,
            Standard.Severity.BLOCKING,
            "no PII",
            new Standard.LlmReviewEnforcement("Look for PII"),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    StandardsRunner.RunReport r =
        runner.run("run-1", List.of(effective(s)), Path.of("."), List.of(), List.of());
    // No deterministic violation for llm-review type — handled separately by reviewer
    assertThat(r.violations()).isEmpty();
  }

  @Test
  void promptComposer_compactPerStandard() {
    Standard s =
        new Standard(
            "std-1",
            null,
            Standard.Severity.BLOCKING,
            "First standard description",
            new Standard.LintEnforcement("eslint", null),
            null,
            List.of(),
            false);
    Standard s2 =
        new Standard(
            "std-2",
            new Standard.Scope(List.of("java"), List.of("src/**"), null, null),
            Standard.Severity.WARNING,
            "Second",
            new Standard.FilePatternEnforcement(List.of("**/.env"), List.of()),
            null,
            List.of(),
            false);
    StandardsPromptComposer composer = new StandardsPromptComposer();
    String block = composer.compose(List.of(effective(s), effective(s2)));
    assertThat(block).contains("=== ENGINEERING_STANDARDS ===");
    assertThat(block).contains("[std-1] severity=BLOCKING");
    assertThat(block).contains("First standard description");
    assertThat(block).contains("scope: lang=java");
  }

  @Test
  void promptComposer_emptyWhenNoStandards() {
    assertThat(new StandardsPromptComposer().compose(List.of())).isEmpty();
    assertThat(new StandardsPromptComposer().compose(null)).isEmpty();
  }

  @Test
  void promptComposer_marksOverriddenAndLockedStandards() {
    Standard s =
        new Standard(
            "std-1",
            null,
            Standard.Severity.WARNING,
            "downgraded",
            new Standard.LintEnforcement("eslint", null),
            null,
            List.of(),
            true);
    EffectiveStandardsResolver.Effective e =
        new EffectiveStandardsResolver.Effective(
            s, EffectiveStandardsResolver.Source.ORG, true, Standard.Severity.BLOCKING, true);
    String block = new StandardsPromptComposer().compose(List.of(e));
    assertThat(block).contains("(project downgraded from BLOCKING)");
    assertThat(block).contains("[LOCKED]");
  }

  // RR-1611 — degraded AST / file-pattern checks must be reported as non-EVALUATED, not clean.

  @Test
  void filePatternStandard_recordsEvaluatedStatus() {
    Standard s =
        new Standard(
            "no-skip-migrations",
            null,
            Standard.Severity.BLOCKING,
            "no SKIP migrations",
            new Standard.FilePatternEnforcement(List.of("**/V*__SKIP*.sql"), List.of()),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    StandardsRunner.RunReport r =
        runner.run("run-1", List.of(effective(s)), Path.of("."), List.of("db/x.sql"), List.of());
    assertThat(r.evaluationStatuses().get("no-skip-migrations"))
        .isEqualTo(com.navneet.executespec.standards.enforcement.EvaluationStatus.EVALUATED);
  }

  @Test
  void filePatternStandard_withNoUsablePattern_isInvalidRuleNotClean() {
    Standard s =
        new Standard(
            "empty-file-pattern",
            null,
            Standard.Severity.BLOCKING,
            "misconfigured",
            new Standard.FilePatternEnforcement(List.of(), List.of()),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    StandardsRunner.RunReport r =
        runner.run("run-1", List.of(effective(s)), Path.of("."), List.of("a.txt"), List.of());
    assertThat(r.violations()).isEmpty();
    assertThat(r.evaluationStatuses().get("empty-file-pattern"))
        .isEqualTo(com.navneet.executespec.standards.enforcement.EvaluationStatus.INVALID_RULE);
  }

  @Test
  void astStandard_invalidRegex_isInvalidRuleNotClean() {
    Standard s =
        new Standard(
            "bad-naming",
            null,
            Standard.Severity.BLOCKING,
            "naming",
            new Standard.AstCheckEnforcement(
                "naming-pattern", java.util.Map.of("regex", "["), null),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    StandardsRunner.RunReport r =
        runner.run(
            "run-1", List.of(effective(s)), Path.of("."), List.of(), List.of(Path.of("A.java")));
    assertThat(r.violations()).isEmpty();
    assertThat(r.evaluationStatuses().get("bad-naming"))
        .isEqualTo(com.navneet.executespec.standards.enforcement.EvaluationStatus.INVALID_RULE);
  }

  @Test
  void astStandard_missingRequiredParameter_isInvalidRuleNotClean() {
    Standard s =
        new Standard(
            "no-import-param",
            null,
            Standard.Severity.BLOCKING,
            "forbidden import",
            new Standard.AstCheckEnforcement("forbidden-import", java.util.Map.of(), null),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    StandardsRunner.RunReport r =
        runner.run(
            "run-1", List.of(effective(s)), Path.of("."), List.of(), List.of(Path.of("A.java")));
    assertThat(r.evaluationStatuses().get("no-import-param"))
        .isEqualTo(com.navneet.executespec.standards.enforcement.EvaluationStatus.INVALID_RULE);
  }

  @Test
  void astStandard_allFilesUnreadable_isUnavailableNotClean() {
    Standard s =
        new Standard(
            "forbidden-bad",
            null,
            Standard.Severity.BLOCKING,
            "forbidden import",
            new Standard.AstCheckEnforcement(
                "forbidden-import", java.util.Map.of("import", "com.bad"), null),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    // The path does not exist on disk, so the default reader returns null for every file.
    StandardsRunner.RunReport r =
        runner.run(
            "run-1",
            List.of(effective(s)),
            Path.of("."),
            List.of(),
            List.of(Path.of("does-not-exist-xyz.java")));
    assertThat(r.evaluationStatuses().get("forbidden-bad"))
        .isEqualTo(com.navneet.executespec.standards.enforcement.EvaluationStatus.UNAVAILABLE);
  }

  @Test
  void astStandard_unsupportedRuleKind_isInvalidRuleNotClean() {
    Standard s =
        new Standard(
            "future-kind",
            null,
            Standard.Severity.BLOCKING,
            "future",
            new Standard.AstCheckEnforcement("future-rule", java.util.Map.of(), null),
            null,
            List.of(),
            false);
    StandardsRunner runner =
        new StandardsRunner(new FilePatternEnforcer(), new AstCheckEnforcer(), List.of());
    StandardsRunner.RunReport r =
        runner.run(
            "run-1", List.of(effective(s)), Path.of("."), List.of(), List.of(Path.of("A.java")));
    assertThat(r.evaluationStatuses().get("future-kind"))
        .isEqualTo(com.navneet.executespec.standards.enforcement.EvaluationStatus.INVALID_RULE);
  }

  private EffectiveStandardsResolver.Effective effective(Standard s) {
    return new EffectiveStandardsResolver.Effective(
        s, EffectiveStandardsResolver.Source.ORG, false, s.severity(), false);
  }
}
