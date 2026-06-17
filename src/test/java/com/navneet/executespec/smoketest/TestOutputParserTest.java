package com.navneet.executespec.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.navneet.executespec.smoketest.TestCommandResolver.Framework;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.Test;

/**
 * Pins the multi-framework test-output parser against real runner output. The contract that matters
 * most: unparseable output returns empty (never a fabricated count) — see {@code
 * garbage_returns_empty}. RL-2026-05-001.
 */
class TestOutputParserTest {

  private static TestExecutionSummary ok(Optional<TestExecutionSummary> o) {
    assertTrue(o.isPresent(), "expected a parsed summary");
    return o.get();
  }

  @Test
  void pytest_passed_and_failed() {
    var s =
        ok(TestOutputParser.parse(Framework.PYTEST, "==== 5 passed, 2 failed in 1.23s ====", 1));
    assertEquals(7, s.getTotalTests());
    assertEquals(5, s.getPassed());
    assertEquals(2, s.getFailed());
  }

  @Test
  void pytest_all_passed_with_skips() {
    var s =
        ok(TestOutputParser.parse(Framework.PYTEST, "==== 9 passed, 1 skipped in 0.4s ====", 0));
    assertEquals(9, s.getTotalTests());
    assertEquals(9, s.getPassed());
    assertEquals(0, s.getFailed());
    assertEquals(1, s.getSkipped());
  }

  @Test
  void pytest_no_tests_ran_is_zero_not_empty() {
    var s = ok(TestOutputParser.parse(Framework.PYTEST, "no tests ran in 0.01s", 5));
    assertEquals(0, s.getTotalTests());
  }

  @Test
  void jest_total_drives_count() {
    var s =
        ok(TestOutputParser.parse(Framework.JEST, "Tests:       2 failed, 5 passed, 7 total", 1));
    assertEquals(7, s.getTotalTests());
    assertEquals(5, s.getPassed());
    assertEquals(2, s.getFailed());
  }

  @Test
  void surefire_sums_multiple_classes() {
    String out =
        "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0\n"
            + "Tests run: 3, Failures: 1, Errors: 0, Skipped: 1\n";
    var s = ok(TestOutputParser.parse(Framework.SUREFIRE, out, 1));
    assertEquals(7, s.getTotalTests());
    assertEquals(5, s.getPassed()); // 7 - 1 fail - 0 err - 1 skip
    assertEquals(1, s.getFailed());
    assertEquals(1, s.getSkipped());
  }

  @Test
  void cargo_result_line() {
    var s =
        ok(
            TestOutputParser.parse(
                Framework.CARGO, "test result: ok. 8 passed; 0 failed; 1 ignored;", 0));
    assertEquals(8, s.getTotalTests());
    assertEquals(8, s.getPassed());
  }

  @Test
  void go_counts_pass_fail_markers() {
    String out = "--- PASS: TestA (0.00s)\n--- PASS: TestB (0.00s)\n--- FAIL: TestC (0.01s)\n";
    var s = ok(TestOutputParser.parse(Framework.GO, out, 1));
    assertEquals(3, s.getTotalTests());
    assertEquals(2, s.getPassed());
    assertEquals(1, s.getFailed());
  }

  @Test
  void go_no_test_files_is_zero() {
    var s = ok(TestOutputParser.parse(Framework.GO, "?   mymod   [no test files]", 0));
    assertEquals(0, s.getTotalTests());
  }

  @Test
  void garbage_returns_empty_never_fabricates() {
    // The contract: if we can't read a real count, do NOT make one up. The caller falls back to
    // LLM coverage, which is better than a wrong hard number.
    assertTrue(TestOutputParser.parse(Framework.PYTEST, "Segmentation fault", 139).isEmpty());
    assertTrue(TestOutputParser.parse(Framework.JEST, "command not found: jest", 127).isEmpty());
    assertTrue(TestOutputParser.parse(Framework.SUREFIRE, "BUILD FAILURE\nno tests", 1).isEmpty());
    assertTrue(TestOutputParser.parse(Framework.CARGO, "error[E0433]", 101).isEmpty());
    assertTrue(TestOutputParser.parse(Framework.UNKNOWN, "5 passed", 0).isEmpty());
  }

  // ── JUnit XML report fallback (RL-2026-06-049) ────────────────────────────────
  // The `mvn -q test` regression: stdout has no "Tests run:" line, so the stdout parser returns
  // empty and a run whose tests FAILED was scored UNKNOWN (no penalty / no failed-tests cap). The
  // surefire/gradle TEST-*.xml reports are written regardless and carry the real counts.

  @Test
  void junit_xml_reports_sum_across_files() {
    String a = "<testsuite name=\"FooTest\" tests=\"4\" failures=\"0\" errors=\"0\" skipped=\"0\">";
    String b = "<testsuite name=\"BarTest\" tests=\"3\" failures=\"1\" errors=\"1\" skipped=\"1\">";
    var s = ok(TestOutputParser.parseJunitXmlReports(java.util.List.of(a, b)));
    assertEquals(7, s.getTotalTests());
    assertEquals(1, s.getFailed());
    assertEquals(1, s.getErrors());
    assertEquals(1, s.getSkipped());
    assertEquals(4, s.getPassed()); // 7 - 1 fail - 1 err - 1 skip
  }

  @Test
  void junit_xml_reports_attribute_order_independent() {
    String xml = "<testsuite failures=\"2\" name=\"X\" skipped=\"0\" tests=\"5\" errors=\"0\">";
    var s = ok(TestOutputParser.parseJunitXmlReports(java.util.List.of(xml)));
    assertEquals(5, s.getTotalTests());
    assertEquals(2, s.getFailed());
    assertEquals(3, s.getPassed());
  }

  @Test
  void junit_xml_reports_empty_when_no_testsuite() {
    assertTrue(TestOutputParser.parseJunitXmlReports(java.util.List.of()).isEmpty());
    assertTrue(
        TestOutputParser.parseJunitXmlReports(java.util.List.of("<not-a-report/>")).isEmpty());
  }
}
