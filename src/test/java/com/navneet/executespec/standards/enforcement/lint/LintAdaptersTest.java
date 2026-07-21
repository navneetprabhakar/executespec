package com.navneet.executespec.standards.enforcement.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Tests for the E3.4 lint adapters — focuses on CLI-not-found graceful degradation + the JSON/XML
 * parsing helpers (no real subprocess invocation in unit tests).
 */
class LintAdaptersTest {

  /** A policy that admits everything, so parse/exit-code behaviour can still be asserted. */
  private static LintExecutionPolicy allowing(String executable) {
    return new LintExecutionPolicy(new LintExecutionProperties()) {
      @Override
      public Admission admit(
          String tool, Path repoRoot, Path configPath, List<java.nio.file.Path> targetFiles) {
        return new Admission(executable, null, List.of(), null);
      }
    };
  }

  private static LintProcessRunner stubRunner(LintProcessRunner.Result result) {
    return new LintProcessRunner(new LintExecutionProperties(), null) {
      @Override
      public Result run(String runId, Path workingDir, List<String> argv, long timeoutSeconds) {
        return result;
      }
    };
  }

  @Test
  void eslint_unavailableWhenCliMissing() {
    LintProcessRunner stub =
        stubRunner(
            new LintProcessRunner.Result(
                false, -1, null, null, "linter '/opt/eslint' could not be started"));
    EslintAdapter a = new EslintAdapter(stub, allowing("/opt/eslint"), new ObjectMapper());
    LintResult r = a.run("run-1", Path.of("."), null, List.of());
    assertThat(r.status()).isEqualTo(LintResult.Status.UNAVAILABLE);
    assertThat(r.violations()).isEmpty();
    assertThat(r.tool()).isEqualTo("eslint");
  }

  @Test
  void eslint_deniedByPolicy_isUnavailableAndNeverStartsAProcess() {
    // RR-1051 — with host execution off (the default) no subprocess may start at all.
    LintProcessRunner explodingRunner =
        new LintProcessRunner(new LintExecutionProperties(), null) {
          @Override
          public Result run(String runId, Path workingDir, List<String> argv, long timeoutSeconds) {
            throw new AssertionError("policy denied the run; no process may be started");
          }
        };
    EslintAdapter a =
        new EslintAdapter(
            explodingRunner,
            new LintExecutionPolicy(new LintExecutionProperties()),
            new ObjectMapper());
    LintResult r = a.run("run-1", Path.of("."), null, List.of());
    assertThat(r.status()).isEqualTo(LintResult.Status.UNAVAILABLE);
    assertThat(r.failureReason()).contains("host-execution-enabled=false");
  }

  @Test
  void eslint_parsesJsonOutput_perFilePerMessage() throws Exception {
    EslintAdapter a = new EslintAdapter(null, null, new ObjectMapper());
    String stdout =
        """
[
  {"filePath": "/repo/a.js", "messages": [
    {"ruleId": "no-unused-vars", "severity": 2, "line": 5, "column": 3, "message": "x is unused"},
    {"ruleId": "semi", "severity": 1, "line": 10, "column": 0, "message": "missing semi"}
  ]},
  {"filePath": "/repo/b.js", "messages": []}
]
""";
    List<LintViolation> v = a.parseJsonOutput(stdout);
    assertThat(v).hasSize(2);
    assertThat(v.get(0).file()).endsWith("a.js");
    assertThat(v.get(0).line()).isEqualTo(5);
    assertThat(v.get(0).ruleId()).isEqualTo("no-unused-vars");
    assertThat(v.get(1).ruleId()).isEqualTo("semi");
  }

  @Test
  void eslint_emptyStdoutReturnsEmpty() throws Exception {
    EslintAdapter a = new EslintAdapter(null, null, new ObjectMapper());
    assertThat(a.parseJsonOutput(null)).isEmpty();
    assertThat(a.parseJsonOutput("")).isEmpty();
    assertThat(a.parseJsonOutput("   ")).isEmpty();
  }

  @Test
  void eslint_violationsFound_butExitNonZero_stillSuccess() {
    // ESLint exits non-zero when violations found — adapter should still
    // mark SUCCESS and return the parsed violations (not FAILED).
    LintProcessRunner stub =
        stubRunner(
            new LintProcessRunner.Result(
                true,
                1,
                "[{\"filePath\":\"/repo/a.js\",\"messages\":[{\"ruleId\":\"x\",\"line\":1,\"column\":1,\"message\":\"m\"}]}]",
                "",
                null));
    EslintAdapter a = new EslintAdapter(stub, allowing("/opt/eslint"), new ObjectMapper());
    LintResult r = a.run("run-1", Path.of("."), null, List.of());
    assertThat(r.status()).isEqualTo(LintResult.Status.SUCCESS);
    assertThat(r.violations()).hasSize(1);
  }

  @Test
  void checkstyle_unavailableWhenCliMissing() {
    LintProcessRunner stub =
        stubRunner(
            new LintProcessRunner.Result(
                false, -1, null, null, "linter '/opt/checkstyle' could not be started"));
    CheckstyleAdapter a = new CheckstyleAdapter(stub, allowing("/opt/checkstyle"));
    LintResult r = a.run("run-1", Path.of("."), null, List.of());
    assertThat(r.status()).isEqualTo(LintResult.Status.UNAVAILABLE);
    assertThat(r.tool()).isEqualTo("checkstyle");
  }

  @Test
  void checkstyle_parsesXmlOutput_perFilePerError() {
    CheckstyleAdapter a = new CheckstyleAdapter(null, null);
    String xml =
        """
<?xml version="1.0" encoding="UTF-8"?>
<checkstyle version="10.0">
  <file name="/repo/Foo.java">
    <error line="12" column="3" severity="error" message="line too long" source="LineLengthCheck"/>
    <warning line="20" severity="warning" message="trailing whitespace" source="WhitespaceCheck"/>
  </file>
  <file name="/repo/Bar.java"></file>
</checkstyle>
""";
    List<LintViolation> v = a.parseXmlOutput(xml);
    assertThat(v).hasSize(2);
    assertThat(v.get(0).file()).endsWith("Foo.java");
    assertThat(v.get(0).line()).isEqualTo(12);
    assertThat(v.get(0).column()).isEqualTo(3);
    assertThat(v.get(0).ruleId()).isEqualTo("LineLengthCheck");
    assertThat(v.get(1).line()).isEqualTo(20);
    assertThat(v.get(1).column()).isZero(); // missing column attr → 0
  }

  @Test
  void checkstyle_emptyOrMalformedXml_returnsEmpty() {
    CheckstyleAdapter a = new CheckstyleAdapter(null, null);
    assertThat(a.parseXmlOutput(null)).isEmpty();
    assertThat(a.parseXmlOutput("")).isEmpty();
    assertThat(a.parseXmlOutput("not xml")).isEmpty();
  }

  @Test
  void checkstyle_parseAttrs_handlesMultipleAttrsAndQuoting() {
    java.util.Map<String, String> m =
        CheckstyleAdapter.parseAttrs("line=\"42\" message=\"hello world\" source=\"X.Y\"");
    assertThat(m)
        .containsEntry("line", "42")
        .containsEntry("message", "hello world")
        .containsEntry("source", "X.Y");
  }

  @Test
  void lintResult_okIsTrueOnlyForSuccess() {
    assertThat(new LintResult("x", LintResult.Status.SUCCESS, List.of(), null).ok()).isTrue();
    assertThat(new LintResult("x", LintResult.Status.FAILED, List.of(), "e").ok()).isFalse();
    assertThat(new LintResult("x", LintResult.Status.UNAVAILABLE, List.of(), "u").ok()).isFalse();
  }
}
