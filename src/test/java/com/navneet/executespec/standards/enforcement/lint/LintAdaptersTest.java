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

  // ── EslintAdapter ───────────────────────────────────────────────────────

  @Test
  void eslint_unavailableWhenCliMissing() {
    LintProcessRunner stub =
        new LintProcessRunner() {
          @Override
          public Result run(Path workingDir, List<String> argv, long timeoutSeconds) {
            return new Result(false, -1, null, null, "CLI 'npx' not available on PATH");
          }
        };
    EslintAdapter a = new EslintAdapter(stub, new ObjectMapper());
    LintResult r = a.run(Path.of("."), null, List.of());
    assertThat(r.status()).isEqualTo(LintResult.Status.UNAVAILABLE);
    assertThat(r.violations()).isEmpty();
    assertThat(r.tool()).isEqualTo("eslint");
  }

  @Test
  void eslint_parsesJsonOutput_perFilePerMessage() throws Exception {
    EslintAdapter a = new EslintAdapter(null, new ObjectMapper());
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
    EslintAdapter a = new EslintAdapter(null, new ObjectMapper());
    assertThat(a.parseJsonOutput(null)).isEmpty();
    assertThat(a.parseJsonOutput("")).isEmpty();
    assertThat(a.parseJsonOutput("   ")).isEmpty();
  }

  @Test
  void eslint_violationsFound_butExitNonZero_stillSuccess() {
    // ESLint exits non-zero when violations found — adapter should still
    // mark SUCCESS and return the parsed violations (not FAILED).
    LintProcessRunner stub =
        new LintProcessRunner() {
          @Override
          public Result run(Path workingDir, List<String> argv, long timeoutSeconds) {
            String stdout =
                "[{\"filePath\":\"/repo/a.js\",\"messages\":[{\"ruleId\":\"x\",\"line\":1,\"column\":1,\"message\":\"m\"}]}]";
            return new Result(true, 1, stdout, "", null);
          }
        };
    EslintAdapter a = new EslintAdapter(stub, new ObjectMapper());
    LintResult r = a.run(Path.of("."), null, List.of());
    assertThat(r.status()).isEqualTo(LintResult.Status.SUCCESS);
    assertThat(r.violations()).hasSize(1);
  }

  // ── CheckstyleAdapter ──────────────────────────────────────────────────

  @Test
  void checkstyle_unavailableWhenCliMissing() {
    LintProcessRunner stub =
        new LintProcessRunner() {
          @Override
          public Result run(Path workingDir, List<String> argv, long timeoutSeconds) {
            return new Result(false, -1, null, null, "CLI 'checkstyle' not available on PATH");
          }
        };
    CheckstyleAdapter a = new CheckstyleAdapter(stub);
    LintResult r = a.run(Path.of("."), null, List.of());
    assertThat(r.status()).isEqualTo(LintResult.Status.UNAVAILABLE);
    assertThat(r.tool()).isEqualTo("checkstyle");
  }

  @Test
  void checkstyle_parsesXmlOutput_perFilePerError() {
    CheckstyleAdapter a = new CheckstyleAdapter(null);
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
    CheckstyleAdapter a = new CheckstyleAdapter(null);
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

  // ── LintResult ──────────────────────────────────────────────────────────

  @Test
  void lintResult_okIsTrueOnlyForSuccess() {
    assertThat(new LintResult("x", LintResult.Status.SUCCESS, List.of(), null).ok()).isTrue();
    assertThat(new LintResult("x", LintResult.Status.FAILED, List.of(), "e").ok()).isFalse();
    assertThat(new LintResult("x", LintResult.Status.UNAVAILABLE, List.of(), "u").ok()).isFalse();
  }
}
