package com.navneet.executespec.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;

import com.navneet.executespec.agent.WorkerResult;

import org.junit.jupiter.api.Test;

/**
 * 2026-05-08 — pins the structured {@code VERDICT:} prefix parsing on the reviewer's {@code
 * completeTask} summary. Replaces the legacy substring heuristic ({@code .contains("no issues")} /
 * {@code .contains("found 0")}) which was brittle to model phrasing variation. The reviewer prompts
 * (general + wiring) now require the prefix as the first line; this test class pins both the
 * structured-path and the backward-compat heuristic-path behaviour.
 *
 * <p>Reflection is used to drive the private {@code determineOverall} method directly — mocking the
 * full {@link ReviewerAgent} dependency graph is overkill for what's logically a pure function on
 * the worker results + build flag.
 */
class ReviewerAgentVerdictParsingTest {

  /** Build a minimal {@link WorkerResult} with the supplied completion summary. */
  private static WorkerResult workerWithSummary(String summary) {
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .iterations(5)
        .toolCallsDispatched(10)
        .completionSummary(summary)
        .finalAssistantText("")
        .toolCallLog(List.of())
        .totalLatencyMs(1000L)
        .build();
  }

  /** Drives the package-private {@code determineOverall} via reflection. */
  private static ReviewerResult.Overall determineOverall(
      ReviewerAgent agent, WorkerResult general, WorkerResult wiring, boolean buildPassed)
      throws Exception {
    Method m =
        ReviewerAgent.class.getDeclaredMethod(
            "determineOverall", WorkerResult.class, WorkerResult.class, boolean.class);
    m.setAccessible(true);
    return (ReviewerResult.Overall) m.invoke(agent, general, wiring, buildPassed);
  }

  /**
   * Constructs a ReviewerAgent with all-null deps — {@code determineOverall} doesn't touch any
   * field, so the constructor's @RequiredArgsConstructor accepts the nulls and we can unit-test the
   * private helper without a Spring context.
   */
  private static ReviewerAgent agent() {
    return new ReviewerAgent(null, null, null, null, null);
  }

  // ── Structured-verdict path ─────────────────────────────────────────────

  @Test
  void both_clean_with_build_pass_returns_clean() throws Exception {
    var g = workerWithSummary("VERDICT: CLEAN\nNo in-scope issues found.");
    var w = workerWithSummary("VERDICT: CLEAN\nWiring complete.");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }

  @Test
  void general_fixed_wiring_clean_build_pass_returns_fixed_issues() throws Exception {
    var g = workerWithSummary("VERDICT: FIXED_ISSUES\nFound 2, fixed both.");
    var w = workerWithSummary("VERDICT: CLEAN\nNothing to wire.");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void wiring_fixed_general_clean_build_pass_returns_fixed_issues() throws Exception {
    var g = workerWithSummary("VERDICT: CLEAN\n");
    var w = workerWithSummary("VERDICT: FIXED_ISSUES\nWired 1 module.");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void general_unresolved_dominates_returns_unresolved() throws Exception {
    var g =
        workerWithSummary("VERDICT: UNRESOLVED\nFound bug in auth, can't fix without more info.");
    var w = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.UNRESOLVED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void wiring_unresolved_dominates_returns_unresolved() throws Exception {
    var g = workerWithSummary("VERDICT: CLEAN\n");
    var w = workerWithSummary("VERDICT: UNRESOLVED\nMissing entry point, route file unclear.");
    assertEquals(ReviewerResult.Overall.UNRESOLVED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void build_failure_dominates_clean_verdict_returns_unresolved() throws Exception {
    // Even if the agent says "VERDICT: CLEAN", a failed build is ground truth.
    var g = workerWithSummary("VERDICT: CLEAN\n");
    var w = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(
        ReviewerResult.Overall.UNRESOLVED_ISSUES,
        determineOverall(agent(), g, w, /* buildPassed */ false));
  }

  @Test
  void verdict_prefix_case_insensitive() throws Exception {
    var g = workerWithSummary("verdict: clean\nlowercase prefix should still parse.");
    var w = workerWithSummary("Verdict: Clean\nMixed case should also parse.");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }

  @Test
  void verdict_fixed_alias_no_underscore_accepted() throws Exception {
    // The prompt says FIXED_ISSUES but models occasionally drop the underscore. Accept both.
    var g = workerWithSummary("VERDICT: FIXED\nFixed something.");
    var w = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void wiring_null_treated_as_clean() throws Exception {
    // Some configurations skip the wiring pass; null wiring result must not block CLEAN.
    var g = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, null, true));
  }

  // ── Backward-compat heuristic path (pre-prompt-update runs) ─────────────

  @Test
  void no_verdict_prefix_falls_back_to_substring_no_issues() throws Exception {
    // Pre-prompt runs: substring heuristic still works. "no in-scope issues found" → CLEAN.
    var g = workerWithSummary("no in-scope issues found");
    var w = workerWithSummary("no wiring issues found");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }

  @Test
  void no_verdict_prefix_falls_back_to_substring_found_zero() throws Exception {
    var g = workerWithSummary("found 0 issues during the general pass");
    var w = workerWithSummary("found 0 wiring problems");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }

  @Test
  void no_verdict_prefix_with_real_issues_falls_to_fixed() throws Exception {
    // Free text describing a fix; build passed → FIXED_ISSUES.
    var g = workerWithSummary("Found 2 issues, fixed both.");
    var w = workerWithSummary("Wired 1 module into main.rs");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void unparseable_verdict_value_falls_through_to_heuristic() throws Exception {
    // Agent emitted "VERDICT:" prefix but with garbage value → fall through to heuristic.
    var g = workerWithSummary("VERDICT: XYZZY\nfound 0 issues");
    var w = workerWithSummary("VERDICT: ???\nno wiring issues");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }
}
