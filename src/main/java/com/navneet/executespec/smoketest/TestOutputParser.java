package com.navneet.executespec.smoketest;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.smoketest.TestCommandResolver.Framework;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

/**
 * Parses raw test-runner stdout/stderr into a {@link TestExecutionSummary} per framework.
 * Rationale: RL-2026-05-001.
 *
 * <p><b>Hard rule:</b> when the output can't be confidently parsed into a count, return {@link
 * Optional#empty()} — NEVER fabricate a number. A wrong count poisons the verifiedScore TESTS slot
 * worse than no count (the caller falls back to LLM-judged coverage on empty). The only count we
 * trust is one the framework explicitly printed.
 */
public final class TestOutputParser {

  private TestOutputParser() {}

  // pytest: "===== 5 passed, 2 failed, 1 skipped in 1.23s ====="  (any subset of the clauses)
  private static final Pattern PYTEST_PASSED = Pattern.compile("(\\d+)\\s+passed");
  private static final Pattern PYTEST_FAILED = Pattern.compile("(\\d+)\\s+failed");
  private static final Pattern PYTEST_ERROR = Pattern.compile("(\\d+)\\s+error");
  private static final Pattern PYTEST_SKIPPED = Pattern.compile("(\\d+)\\s+skipped");

  // jest: "Tests:       2 failed, 5 passed, 7 total"
  private static final Pattern JEST_TOTAL = Pattern.compile("Tests:.*?(\\d+)\\s+total");
  private static final Pattern JEST_PASSED = Pattern.compile("Tests:.*?(\\d+)\\s+passed");
  private static final Pattern JEST_FAILED = Pattern.compile("Tests:.*?(\\d+)\\s+failed");
  private static final Pattern JEST_SKIPPED = Pattern.compile("Tests:.*?(\\d+)\\s+skipped");

  // surefire/gradle: "Tests run: 7, Failures: 2, Errors: 0, Skipped: 1"  (may appear many times;
  // we sum every occurrence so multi-module / multi-class runs aggregate correctly)
  private static final Pattern SUREFIRE_LINE =
      Pattern.compile(
          "Tests"
              + " run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)(?:,\\s*Skipped:\\s*(\\d+))?");

  // cargo: "test result: ok. 5 passed; 0 failed; 1 ignored; ..."  (one per test binary)
  private static final Pattern CARGO_LINE =
      Pattern.compile(
          "test result:\\s*\\w+\\.\\s*(\\d+)\\s+passed;\\s*(\\d+)\\s+failed(?:;\\s*(\\d+)\\s+ignored)?");

  // go: per-test "--- PASS:" / "--- FAIL:" lines (the most reliable count for `go test ./...`)
  private static final Pattern GO_PASS = Pattern.compile("(?m)^\\s*--- PASS:");
  private static final Pattern GO_FAIL = Pattern.compile("(?m)^\\s*--- FAIL:");

  /** Parse {@code output} for {@code framework}. Empty when no trustworthy count is present. */
  public static Optional<TestExecutionSummary> parse(
      Framework framework, String output, int exitCode) {
    if (output == null) output = "";
    return switch (framework) {
      case PYTEST -> parsePytest(output);
      case JEST -> parseJest(output);
      case SUREFIRE -> parseSurefire(output);
      case CARGO -> parseCargo(output);
      case GO -> parseGo(output, exitCode);
      case UNKNOWN -> Optional.empty();
    };
  }

  private static Optional<TestExecutionSummary> parsePytest(String out) {
    Integer passed = firstInt(PYTEST_PASSED, out);
    Integer failed = firstInt(PYTEST_FAILED, out);
    Integer errors = firstInt(PYTEST_ERROR, out);
    Integer skipped = firstInt(PYTEST_SKIPPED, out);
    if (passed == null && failed == null && errors == null) {
      // "no tests ran" is a real, parseable pytest outcome → 0/0, not empty.
      if (out.contains("no tests ran")) return Optional.of(zero());
      return Optional.empty();
    }
    int p = orZero(passed), f = orZero(failed), e = orZero(errors), s = orZero(skipped);
    return Optional.of(summary(p + f + e, p, f, e, s));
  }

  private static Optional<TestExecutionSummary> parseJest(String out) {
    Integer total = firstInt(JEST_TOTAL, out);
    if (total == null) return Optional.empty();
    int p = orZero(firstInt(JEST_PASSED, out));
    int f = orZero(firstInt(JEST_FAILED, out));
    int s = orZero(firstInt(JEST_SKIPPED, out));
    return Optional.of(summary(total, p, f, 0, s));
  }

  private static Optional<TestExecutionSummary> parseSurefire(String out) {
    Matcher m = SUREFIRE_LINE.matcher(out);
    boolean any = false;
    int total = 0, fail = 0, err = 0, skip = 0;
    while (m.find()) {
      any = true;
      total += Integer.parseInt(m.group(1));
      fail += Integer.parseInt(m.group(2));
      err += Integer.parseInt(m.group(3));
      skip += m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
    }
    if (!any) return Optional.empty();
    int passed = Math.max(0, total - fail - err - skip);
    return Optional.of(summary(total, passed, fail, err, skip));
  }

  private static Optional<TestExecutionSummary> parseCargo(String out) {
    Matcher m = CARGO_LINE.matcher(out);
    boolean any = false;
    int passed = 0, failed = 0, ignored = 0;
    while (m.find()) {
      any = true;
      passed += Integer.parseInt(m.group(1));
      failed += Integer.parseInt(m.group(2));
      ignored += m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
    }
    if (!any) return Optional.empty();
    return Optional.of(summary(passed + failed, passed, failed, 0, ignored));
  }

  private static Optional<TestExecutionSummary> parseGo(String out, int exitCode) {
    int pass = count(GO_PASS, out);
    int fail = count(GO_FAIL, out);
    if (pass == 0 && fail == 0) {
      // `go test` with no test files prints "no test files" and exits 0 → 0/0, parseable.
      if (out.contains("no test files") || out.contains("[no tests to run]")) {
        return Optional.of(zero());
      }
      return Optional.empty();
    }
    return Optional.of(summary(pass + fail, pass, fail, 0, 0));
  }

  private static Integer firstInt(Pattern p, String s) {
    Matcher m = p.matcher(s);
    return m.find() ? Integer.parseInt(m.group(1)) : null;
  }

  private static int count(Pattern p, String s) {
    Matcher m = p.matcher(s);
    int c = 0;
    while (m.find()) c++;
    return c;
  }

  private static int orZero(Integer i) {
    return i == null ? 0 : i;
  }

  private static TestExecutionSummary summary(
      int total, int passed, int failed, int errors, int skipped) {
    return TestExecutionSummary.builder()
        .totalTests(total)
        .passed(passed)
        .failed(failed)
        .errors(errors)
        .skipped(skipped)
        .build();
  }

  private static TestExecutionSummary zero() {
    return summary(0, 0, 0, 0, 0);
  }
}
