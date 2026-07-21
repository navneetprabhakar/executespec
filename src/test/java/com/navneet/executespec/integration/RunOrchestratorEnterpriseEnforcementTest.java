package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.gate.PlanBreakingChangeReport;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.standards.StandardsRunner;
import com.navneet.executespec.standards.StandardsViolation;
import com.navneet.executespec.standards.enforcement.lint.LintResult;
import com.navneet.executespec.standards.model.Standard;

import org.junit.jupiter.api.Test;

/**
 * RL-2026-05-037 — pins the pure mappers that wire the (formerly dead) enterprise enforcement
 * engines into the run pipeline: {@code StandardsRunner} violations → persistable {@code
 * standards_violation} rows, and breaking-change report rows → report-only run issues. These
 * mappers carry the field-mapping + severity + cap + consumer-formatting logic most likely to
 * drift; the surrounding {@code RunOrchestrator} glue (flag gate, workspace walk, save loop) is
 * thin. Testing the static mappers directly avoids constructing the ~40-dependency god class.
 */
class RunOrchestratorEnterpriseEnforcementTest {

  // Standards violations → persistable rows

  @Test
  void standards_violations_map_to_rows_with_run_and_project_stamped() {
    UUID runId = UUID.randomUUID();
    StandardsRunner.RunReport report =
        new StandardsRunner.RunReport(
            List.of(
                new StandardsRunner.AggregatedViolation(
                    "no-system-out",
                    Standard.Severity.BLOCKING,
                    "AST_CHECK",
                    "src/Main.java:42",
                    "System.out.println is banned")),
            Map.of());

    List<StandardsViolation> rows =
        RunStandardsEnforcer.toStandardsViolationRows(runId, "proj-1", report, 200);

    assertEquals(1, rows.size());
    StandardsViolation v = rows.get(0);
    assertEquals(runId, v.getRunId());
    assertEquals("proj-1", v.getProjectId());
    assertEquals("no-system-out", v.getStandardId());
    assertEquals("AST_CHECK", v.getEnforcementType());
    assertEquals("BLOCKING", v.getSeverity());
    assertTrue(v.getViolationSummary().contains("System.out.println is banned"));
    assertTrue(
        v.getViolationSummary().contains("src/Main.java:42"),
        "summary must carry the location so the report is actionable");
  }

  @Test
  void standards_row_persistence_is_capped() {
    List<StandardsRunner.AggregatedViolation> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      many.add(
          new StandardsRunner.AggregatedViolation(
              "s" + i, Standard.Severity.WARNING, "LINT", "f:" + i, "msg " + i));
    }
    StandardsRunner.RunReport report = new StandardsRunner.RunReport(many, Map.of());

    List<StandardsViolation> rows =
        RunStandardsEnforcer.toStandardsViolationRows(UUID.randomUUID(), "p", report, 10);

    assertEquals(10, rows.size(), "must honour the persistence cap to avoid a runaway lint dump");
  }

  @Test
  void null_report_yields_no_rows() {
    assertTrue(
        RunStandardsEnforcer.toStandardsViolationRows(UUID.randomUUID(), "p", null, 200).isEmpty());
  }

  @Test
  void blockingAndUnavailableStandardsBecomeCriticalEvidenceAndPreventCleanStatus() {
    StandardsRunner.RunReport report =
        new StandardsRunner.RunReport(
            List.of(
                new StandardsRunner.AggregatedViolation(
                    "no-system-out",
                    Standard.Severity.BLOCKING,
                    "AST_CHECK",
                    "src/Main.java:42",
                    "System.out is banned"),
                new StandardsRunner.AggregatedViolation(
                    "naming",
                    Standard.Severity.WARNING,
                    "FILE_PATTERN",
                    "src/bad.java",
                    "wrong name")),
            Map.of(
                "checkstyle",
                new LintResult(
                    "checkstyle", LintResult.Status.UNAVAILABLE, List.of(), "not installed")));

    var issues = RunStandardsEnforcer.toReviewIssues(report);

    assertEquals(3, issues.size());
    assertEquals(IssueSeverity.CRITICAL, issues.get(0).getSeverity());
    assertEquals(IssueSeverity.MINOR, issues.get(1).getSeverity());
    assertTrue(issues.get(2).getId().startsWith("SYNTH-STANDARD-UNAVAILABLE"));
    assertEquals(
        new RunOrchestrator.StatusAndReason("PARTIAL", "STANDARDS_ENFORCEMENT_UNAVAILABLE"),
        RunOrchestrator.applyStandardsGateStatus(
            new RunOrchestrator.StatusAndReason("COMPLETE", "CLEAN_COMPLETION"), issues));
  }

  @Test
  void blockingStandardAloneProducesExplicitPartialReason() {
    var issues =
        RunStandardsEnforcer.toReviewIssues(
            new StandardsRunner.RunReport(
                List.of(
                    new StandardsRunner.AggregatedViolation(
                        "rule", Standard.Severity.BLOCKING, "AST_CHECK", "f:1", "bad")),
                Map.of()));

    assertEquals(
        new RunOrchestrator.StatusAndReason("PARTIAL", "STANDARDS_BLOCKING_VIOLATION"),
        RunOrchestrator.applyStandardsGateStatus(
            new RunOrchestrator.StatusAndReason("COMPLETE", "CLEAN_COMPLETION"), issues));
  }

  @Test
  void invariantEvidencePreventsCleanStatus() {
    var violation =
        com.navneet.executespec.reviewer.model.ReviewIssue.builder()
            .id("SYNTH-INVARIANT-VIOLATION-1")
            .severity(IssueSeverity.CRITICAL)
            .build();
    var unavailable =
        com.navneet.executespec.reviewer.model.ReviewIssue.builder()
            .id("SYNTH-INVARIANT-UNAVAILABLE-1")
            .severity(IssueSeverity.CRITICAL)
            .build();

    assertEquals(
        new RunOrchestrator.StatusAndReason("PARTIAL", "ARCHITECTURE_INVARIANT_VIOLATION"),
        RunOrchestrator.applyInvariantGateStatus(
            new RunOrchestrator.StatusAndReason("COMPLETE", "CLEAN_COMPLETION"),
            List.of(violation)));
    assertEquals(
        new RunOrchestrator.StatusAndReason("PARTIAL", "INVARIANT_ENFORCEMENT_UNAVAILABLE"),
        RunOrchestrator.applyInvariantGateStatus(
            new RunOrchestrator.StatusAndReason("COMPLETE", "CLEAN_COMPLETION"),
            List.of(unavailable)));
  }

  // Breaking-change report rows → report-only run issues.
  //
  // RR-819 — MINOR is correct HERE and only here: this mapping runs after the worker has already
  // written the change, so it can describe but not refuse. It is the second, tighten-only gate. The
  // control that can actually refuse is PlanContractGateService at the plan-approval gate; these
  // assertions must NOT be read as "report-only is the whole governance story".

  @Test
  void breaking_change_with_consumers_renders_requires_ack_issue() {
    PlanBreakingChangeReport.Report report =
        new PlanBreakingChangeReport.Report(
            List.of(
                new PlanBreakingChangeReport.BreakingChangeRow(
                    UUID.randomUUID(),
                    "orders-api",
                    ContractRegistry.ContractType.OPENAPI,
                    "removed required field `total`",
                    List.of("acme/web", "acme/mobile"),
                    true,
                    "DETERMINISTIC")),
            true);

    var issues = RunBreakingChangeDetector.toBreakingChangeIssues(report);

    assertEquals(1, issues.size());
    var issue = issues.get(0);
    // report-only: MINOR so it surfaces without triggering auto-fix or cratering the score. The
    // approval-time refusal lives in PlanContractGateService, not here.
    assertEquals(IssueSeverity.MINOR, issue.getSeverity());
    assertFalse(issue.isAutoFixed());
    assertTrue(issue.getDescription().contains("orders-api"));
    assertTrue(issue.getDescription().contains("removed required field"));
    assertTrue(issue.getDescription().contains("acme/web"));
    assertTrue(
        issue.getDescription().contains("[REQUIRES ACK]"),
        "a breaking change with declared consumers must carry the typed-ack marker");
  }

  @Test
  void breaking_change_without_consumers_has_no_ack_marker() {
    PlanBreakingChangeReport.Report report =
        new PlanBreakingChangeReport.Report(
            List.of(
                new PlanBreakingChangeReport.BreakingChangeRow(
                    UUID.randomUUID(),
                    "internal-evt",
                    ContractRegistry.ContractType.EVENT_SCHEMA,
                    "renamed field",
                    List.of(),
                    false,
                    "LLM_DERIVED")),
            false);

    var issues = RunBreakingChangeDetector.toBreakingChangeIssues(report);

    assertEquals(1, issues.size());
    assertFalse(issues.get(0).getDescription().contains("[REQUIRES ACK]"));
    assertTrue(issues.get(0).getDescription().contains("no declared consumers"));
  }

  @Test
  void empty_or_null_breaking_report_yields_no_issues() {
    assertTrue(
        RunBreakingChangeDetector.toBreakingChangeIssues(
                new PlanBreakingChangeReport.Report(List.of(), false))
            .isEmpty());
    assertTrue(RunBreakingChangeDetector.toBreakingChangeIssues(null).isEmpty());
  }
}
