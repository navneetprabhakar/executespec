package com.navneet.executespec.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;

import com.navneet.executespec.engine.WorkerResult;

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

  /**
   * Build a minimal {@link WorkerResult} with the supplied completion summary. {@code toolCallLog}
   * is empty by default — used for the "no writes" path that exercises the 2026-05-10 verdict
   * downgrade. Tests that expect a real FIXED_ISSUES verdict should call {@link
   * #workerWithSummaryAndWrites} instead so the write-aware downgrade leaves the verdict intact.
   */
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

  /**
   * Build a {@link WorkerResult} that includes a single {@code writeFile(...)} entry in the tool
   * call log so {@code ReviewerAgent.hasWriteCalls} returns true. Use this in tests that pin a
   * "FIXED_ISSUES with real writes" outcome — without it the 2026-05-10 write-aware downgrade would
   * flip the verdict to CLEAN.
   */
  private static WorkerResult workerWithSummaryAndWrites(String summary) {
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .iterations(5)
        .toolCallsDispatched(10)
        .completionSummary(summary)
        .finalAssistantText("")
        .toolCallLog(List.of("writeFile(path=src/foo.ts)"))
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
    // 7 nulls: WorkerAgent, BuildProjectTool, ObjectMapper, Metrics, FeatureFlagService,
    // PostEditFormatter, ModelResolver. determineOverall touches none of them.
    return new ReviewerAgent(null, null, null, null, null, null, null, null);
  }

  @Test
  void both_clean_with_build_pass_returns_clean() throws Exception {
    var g = workerWithSummary("VERDICT: CLEAN\nNo in-scope issues found.");
    var w = workerWithSummary("VERDICT: CLEAN\nWiring complete.");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }

  @Test
  void general_fixed_wiring_clean_build_pass_returns_fixed_issues() throws Exception {
    var g = workerWithSummaryAndWrites("VERDICT: FIXED_ISSUES\nFound 2, fixed both.");
    var w = workerWithSummary("VERDICT: CLEAN\nNothing to wire.");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void wiring_fixed_general_clean_build_pass_returns_fixed_issues() throws Exception {
    var g = workerWithSummary("VERDICT: CLEAN\n");
    var w = workerWithSummaryAndWrites("VERDICT: FIXED_ISSUES\nWired 1 module.");
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
    var g = workerWithSummaryAndWrites("VERDICT: FIXED\nFixed something.");
    var w = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void wiring_null_treated_as_clean() throws Exception {
    // Some configurations skip the wiring pass; null wiring result must not block CLEAN.
    var g = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, null, true));
  }

  // Backward-compat heuristic path (pre-prompt-update runs)

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
    // Free text describing a fix; build passed → FIXED_ISSUES. Both passes have real
    // writes so the 2026-05-10 write-aware downgrade does NOT fire.
    var g = workerWithSummaryAndWrites("Found 2 issues, fixed both.");
    var w = workerWithSummaryAndWrites("Wired 1 module into main.rs");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  // Write-aware downgrade (2026-05-10 — dev run 61c9fffa)

  @Test
  void fixed_issues_with_zero_writes_downgrades_to_clean() throws Exception {
    // Dev run 61c9fffa regression: both reviewer passes hit STAGNATION_FORCE → SALVAGE,
    // produced no parseable VERDICT prefix, and the legacy fallback path returned
    // FIXED_ISSUES because build passed. With totalWrites=0 across both passes, the
    // reviewer didn't actually fix anything — the verdict should be CLEAN.
    var g = workerWithSummary("Salvage output: scanned 12 files, ran build.");
    var w = workerWithSummary("Salvage output: wiring looks ok.");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }

  @Test
  void fixed_issues_with_general_writes_only_stays_fixed() throws Exception {
    // General pass DID write a file → genuine fix → keep FIXED_ISSUES.
    var g = workerWithSummaryAndWrites("VERDICT: FIXED_ISSUES\nFound + fixed 1.");
    var w = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void fixed_issues_with_wiring_writes_only_stays_fixed() throws Exception {
    // Wiring pass DID write → genuine fix → keep FIXED_ISSUES.
    var g = workerWithSummary("VERDICT: CLEAN\n");
    var w = workerWithSummaryAndWrites("VERDICT: FIXED_ISSUES\nWired 1 import.");
    assertEquals(ReviewerResult.Overall.FIXED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void downgrade_does_not_fire_on_unresolved_path() throws Exception {
    // UNRESOLVED_ISSUES wins over the downgrade — we don't want a build-failed run to
    // sneak through as CLEAN just because the reviewer didn't write.
    var g = workerWithSummary("VERDICT: UNRESOLVED\nCan't fix without spec.");
    var w = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.UNRESOLVED_ISSUES, determineOverall(agent(), g, w, true));
  }

  @Test
  void downgrade_does_not_fire_on_build_failure() throws Exception {
    // Build failure dominates regardless of writes. CLEAN with no writes + failed build
    // should still be UNRESOLVED_ISSUES, not CLEAN.
    var g = workerWithSummary("VERDICT: CLEAN\n");
    var w = workerWithSummary("VERDICT: CLEAN\n");
    assertEquals(ReviewerResult.Overall.UNRESOLVED_ISSUES, determineOverall(agent(), g, w, false));
  }

  @Test
  void hasWriteCalls_recognises_worker_write_aliases() {
    var withWrite =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(1)
            .toolCallsDispatched(1)
            .toolCallLog(List.of("writeFile(path=src/foo.ts)"))
            .totalLatencyMs(0L)
            .build();
    var withEdit =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(1)
            .toolCallsDispatched(1)
            .toolCallLog(List.of("editFile(path=src/foo.ts, edits=2)"))
            .totalLatencyMs(0L)
            .build();
    var withSnakeCaseEdit =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(1)
            .toolCallsDispatched(1)
            .toolCallLog(List.of("edit_file(path=src/foo.ts, edits=2)"))
            .totalLatencyMs(0L)
            .build();
    var withPatch =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(1)
            .toolCallsDispatched(1)
            .toolCallLog(List.of("apply_patch(path=src/foo.ts)"))
            .totalLatencyMs(0L)
            .build();
    var withRefusedWrite =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(1)
            .toolCallsDispatched(1)
            .toolCallLog(List.of("refused writeFile(path=src/foo.ts)"))
            .totalLatencyMs(0L)
            .build();
    var withNeither =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(1)
            .toolCallsDispatched(2)
            .toolCallLog(List.of("readFile(path=src/foo.ts)", "buildProject()"))
            .totalLatencyMs(0L)
            .build();
    org.junit.jupiter.api.Assertions.assertTrue(ReviewerAgent.hasWriteCalls(withWrite));
    org.junit.jupiter.api.Assertions.assertTrue(ReviewerAgent.hasWriteCalls(withEdit));
    org.junit.jupiter.api.Assertions.assertTrue(ReviewerAgent.hasWriteCalls(withSnakeCaseEdit));
    org.junit.jupiter.api.Assertions.assertTrue(ReviewerAgent.hasWriteCalls(withPatch));
    org.junit.jupiter.api.Assertions.assertFalse(ReviewerAgent.hasWriteCalls(withRefusedWrite));
    org.junit.jupiter.api.Assertions.assertFalse(ReviewerAgent.hasWriteCalls(withNeither));
    org.junit.jupiter.api.Assertions.assertFalse(ReviewerAgent.hasWriteCalls(null));
  }

  @Test
  void unparseable_verdict_value_falls_through_to_heuristic() throws Exception {
    // Agent emitted "VERDICT:" prefix but with garbage value → fall through to heuristic.
    var g = workerWithSummary("VERDICT: XYZZY\nfound 0 issues");
    var w = workerWithSummary("VERDICT: ???\nno wiring issues");
    assertEquals(ReviewerResult.Overall.CLEAN, determineOverall(agent(), g, w, true));
  }
}
