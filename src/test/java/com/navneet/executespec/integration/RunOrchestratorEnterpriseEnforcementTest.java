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

  // Breaking-change report rows → report-only run issues

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

    var issues = RunOrchestrator.toBreakingChangeIssues(report);

    assertEquals(1, issues.size());
    var issue = issues.get(0);
    // report-only: MINOR so it surfaces without triggering auto-fix or cratering the score.
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

    var issues = RunOrchestrator.toBreakingChangeIssues(report);

    assertEquals(1, issues.size());
    assertFalse(issues.get(0).getDescription().contains("[REQUIRES ACK]"));
    assertTrue(issues.get(0).getDescription().contains("no declared consumers"));
  }

  @Test
  void empty_or_null_breaking_report_yields_no_issues() {
    assertTrue(
        RunOrchestrator.toBreakingChangeIssues(
                new PlanBreakingChangeReport.Report(List.of(), false))
            .isEmpty());
    assertTrue(RunOrchestrator.toBreakingChangeIssues(null).isEmpty());
  }
}
