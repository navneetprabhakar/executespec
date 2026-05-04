package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

/**
 * R-09 (UX audit, 2026-05-04) regression guard for {@code reconcileWithRunStatus}.
 *
 * <p>Pre-fix the validation report's {@code pipelineResult} could disagree with the run's
 * authoritative {@code status_reason} (set by {@code RunOrchestrator.resolveTerminalStatus}). The
 * frontend papered over the drift via a {@code suppressReason} special case in {@code
 * ValidationReportPanel.tsx}. With the reconciliation in this test pinned, the two signals agree at
 * write time so the UI special case is gone.
 *
 * <p>Each test seeds a {@link ReportInput} with a {@link PipelineRun} carrying the relevant {@code
 * status_reason}, hands a heuristic verdict to {@code reconcileForTest}, and asserts the reconciled
 * output matches the audit-aligned mapping.
 */
class ValidationReportServiceR09Test {

  private final ValidationReportService svc =
      new ValidationReportService(
          null, null, null, null, null, null, mock(FeatureFlagService.class));

  private static ReportInput inputWith(String statusReason) {
    PipelineRun run = new PipelineRun();
    run.setRunId("test-run-id");
    run.setStatusReason(statusReason);
    return new ReportInput(run, List.of(), null, null, List.of());
  }

  @Test
  void clean_completion_promotes_failed_heuristic_to_passed() {
    // The classic R-09 contradiction: reviewer overall verdict + final build pass
    // produced status_reason=CLEAN_COMPLETION but the heuristic saw an unresolved
    // CRITICAL issue and returned FAILED. After reconciliation: PASSED.
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.FAILED, inputWith("CLEAN_COMPLETION"));
    assertEquals(PipelineResult.PASSED, result);
  }

  @Test
  void clean_with_intermediate_warnings_promotes_needs_attention_to_passed() {
    PipelineResult result =
        svc.reconcileForTest(
            PipelineResult.NEEDS_ATTENTION, inputWith("CLEAN_WITH_INTERMEDIATE_WARNINGS"));
    assertEquals(PipelineResult.PASSED, result);
  }

  @Test
  void clean_completion_preserves_passed_with_fixes() {
    // The heuristic's PASSED_WITH_FIXES carries useful information (review issues
    // were auto-fixed). Reconciliation keeps that signal rather than flattening to
    // plain PASSED.
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.PASSED_WITH_FIXES, inputWith("CLEAN_COMPLETION"));
    assertEquals(PipelineResult.PASSED_WITH_FIXES, result);
  }

  @Test
  void final_build_failed_demotes_passed_to_failed() {
    // Should never happen in practice — but if the heuristic disagrees with a
    // hard-failure status_reason, the run wins (the orchestrator considered the
    // reviewer's overall verdict + final build, the heuristic only saw issues).
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.PASSED, inputWith("FINAL_BUILD_FAILED"));
    assertEquals(PipelineResult.FAILED, result);
  }

  @Test
  void all_tasks_failed_forces_failed() {
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.NEEDS_ATTENTION, inputWith("ALL_TASKS_FAILED"));
    assertEquals(PipelineResult.FAILED, result);
  }

  @Test
  void execution_aborted_forces_failed() {
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.PASSED, inputWith("EXECUTION_ABORTED"));
    assertEquals(PipelineResult.FAILED, result);
  }

  @Test
  void reviewer_unresolved_floors_at_needs_attention_when_heuristic_was_passed() {
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.PASSED, inputWith("REVIEWER_UNRESOLVED"));
    assertEquals(PipelineResult.NEEDS_ATTENTION, result);
  }

  @Test
  void reviewer_unresolved_keeps_failed_when_heuristic_was_stricter() {
    // The heuristic spotted an unresolved CRITICAL — keep FAILED. The reviewer's
    // own verdict (UNRESOLVED_ISSUES) was a softer signal here.
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.FAILED, inputWith("REVIEWER_UNRESOLVED"));
    assertEquals(PipelineResult.FAILED, result);
  }

  @Test
  void reviewer_failed_floors_at_needs_attention() {
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.PASSED, inputWith("REVIEWER_FAILED"));
    assertEquals(PipelineResult.NEEDS_ATTENTION, result);
  }

  @Test
  void tests_failed_with_count_demotes_passed_to_needs_attention() {
    // status_reason carries the count: TESTS_FAILED:2_OF_7
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.PASSED, inputWith("TESTS_FAILED:2_OF_7"));
    assertEquals(PipelineResult.NEEDS_ATTENTION, result);
  }

  @Test
  void legacy_unknown_falls_through_to_heuristic() {
    // Pre-v2.5.10 runs only have the heuristic. Don't override.
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.NEEDS_ATTENTION, inputWith("LEGACY_UNKNOWN"));
    assertEquals(PipelineResult.NEEDS_ATTENTION, result);
  }

  @Test
  void null_status_reason_falls_through_to_heuristic() {
    // Defensive: orchestrator didn't set a reason (edge case, shouldn't happen
    // post-V108). Heuristic wins.
    PipelineResult result = svc.reconcileForTest(PipelineResult.PASSED, inputWith(null));
    assertEquals(PipelineResult.PASSED, result);
  }

  @Test
  void null_run_falls_through_to_heuristic() {
    // Defensive: ReportInput without a PipelineRun — shouldn't happen in normal
    // call paths but the signature allows it.
    ReportInput input = new ReportInput(null, List.of(), null, null, List.of());
    PipelineResult result = svc.reconcileForTest(PipelineResult.FAILED, input);
    assertEquals(PipelineResult.FAILED, result);
  }

  @Test
  void unknown_reason_falls_through_to_heuristic() {
    // Future status_reason values we haven't mapped yet: don't override.
    PipelineResult result =
        svc.reconcileForTest(PipelineResult.PASSED, inputWith("SOME_NEW_REASON"));
    assertEquals(PipelineResult.PASSED, result);
  }
}
