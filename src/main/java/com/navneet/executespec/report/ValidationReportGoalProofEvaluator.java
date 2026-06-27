package com.navneet.executespec.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

/**
 * Converts the final validation-report evidence ledger into a conservative run-level completion
 * proof.
 *
 * <p>This is intentionally stricter than the headline score. A run is proven done only when the
 * report result is terminal-success and the relevant evidence rows pass. Missing, warning, or
 * unknown rows keep completion unproven instead of letting the internal score vouch for the run.
 */
public class ValidationReportGoalProofEvaluator {

  private static final Set<String> REQUIRED_PASS_IDS =
      Set.of("code-delivered", "build", "tests", "reviewer", "report-issues");

  private static final Set<String> OPTIONAL_PROOF_IDS =
      Set.of("spec-adherence", "endpoint-coverage", "test-coverage-judgment", "trust-cap");

  public GoalDonenessDecision evaluate(
      String runId, PipelineResult pipelineResult, List<VerificationEvidenceDTO> ledger) {
    return evaluate(runId, null, pipelineResult, ledger);
  }

  public GoalDonenessDecision evaluate(
      String runId,
      String goalSummary,
      PipelineResult pipelineResult,
      List<VerificationEvidenceDTO> ledger) {
    String goal = goal(runId, goalSummary);
    List<VerificationEvidenceDTO> safeLedger = ledger == null ? List.of() : ledger;
    if (safeLedger.isEmpty()) {
      return decision(
          GoalDonenessState.UNCERTAIN,
          RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
          "final validation ledger is missing; run completion is unproven",
          goal,
          List.of(),
          List.of("collect final validation evidence"),
          List.of(),
          List.of("missing validation-report evidence"),
          false,
          false);
    }

    Map<String, VerificationEvidenceDTO> byId = new HashMap<>();
    for (VerificationEvidenceDTO row : safeLedger) {
      if (row != null && row.id() != null && !row.id().isBlank()) {
        byId.put(row.id(), row);
      }
    }

    List<String> completed = new ArrayList<>();
    List<String> incomplete = new ArrayList<>();
    List<String> evidence = new ArrayList<>();
    List<String> risks = new ArrayList<>();

    for (VerificationEvidenceDTO row : safeLedger) {
      if (row == null) continue;
      evidence.add(evidenceLine(row));
      if ("PASS".equalsIgnoreCase(row.status())) {
        completed.add(label(row) + " passed");
      }
    }

    boolean hasFailure = pipelineResult == PipelineResult.FAILED;
    boolean hasUnproven =
        pipelineResult == PipelineResult.NEEDS_ATTENTION || pipelineResult == null;

    if (pipelineResult == PipelineResult.FAILED) {
      risks.add("validation report result is FAILED");
      incomplete.add("pipeline result is FAILED");
    } else if (pipelineResult == PipelineResult.NEEDS_ATTENTION) {
      risks.add("validation report result needs attention");
      incomplete.add("pipeline result needs attention");
    } else if (pipelineResult == null) {
      risks.add("validation report result is unavailable");
      incomplete.add("pipeline result unavailable");
    }

    for (String id : REQUIRED_PASS_IDS) {
      VerificationEvidenceDTO row = byId.get(id);
      String status = status(row);
      if ("PASS".equals(status)) {
        continue;
      }
      if ("FAIL".equals(status)) {
        hasFailure = true;
      } else {
        hasUnproven = true;
      }
      String item = label(row, id) + " is " + status.toLowerCase();
      incomplete.add(item);
      risks.add(item);
    }

    if (reviewerQualityFailed(byId.get("reviewer"))) {
      return decision(
          GoalDonenessState.FAILED,
          RunContinuationDecision.STOP_FAILED,
          "final validation report contains reviewer low-quality evidence",
          goal,
          completed,
          incompleteWith(
              incomplete, "reviewer found unresolved or failed implementation quality evidence"),
          evidence,
          risksWith(
              risks, "reviewer low-quality evidence may require model/provider escalation"),
          false,
          true);
    }

    for (String id : OPTIONAL_PROOF_IDS) {
      VerificationEvidenceDTO row = byId.get(id);
      if (row == null) {
        if ("spec-adherence".equals(id)) {
          hasUnproven = true;
          incomplete.add("spec adherence evidence unavailable");
          risks.add("spec adherence evidence unavailable");
        }
        continue;
      }
      String status = status(row);
      if ("PASS".equals(status)) {
        continue;
      }
      if ("FAIL".equals(status)) {
        hasFailure = true;
      } else {
        hasUnproven = true;
      }
      String item = label(row) + " is " + status.toLowerCase();
      incomplete.add(item);
      risks.add(item);
    }

    boolean terminalSuccess =
        pipelineResult == PipelineResult.PASSED
            || pipelineResult == PipelineResult.PASSED_WITH_FIXES;
    if (terminalSuccess && !hasFailure && !hasUnproven) {
      return decision(
          GoalDonenessState.DONE,
          RunContinuationDecision.STOP_DONE,
          "final validation report proves the run goal is satisfied",
          goal,
          completed,
          List.of(),
          evidence,
          List.of(),
          false,
          false);
    }

    if (hasFailure) {
      return decision(
          GoalDonenessState.FAILED,
          RunContinuationDecision.STOP_FAILED,
          "final validation report contains failed completion evidence",
          goal,
          completed,
          incomplete,
          evidence,
          risks,
          false,
          true);
    }

    return decision(
        GoalDonenessState.UNCERTAIN,
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        "final validation report does not prove run completion",
        goal,
        completed,
        incomplete,
        evidence,
        risks,
        false,
        true);
  }

  private static boolean reviewerQualityFailed(VerificationEvidenceDTO reviewer) {
    String status = status(reviewer);
    return "WARN".equals(status) || "FAIL".equals(status);
  }

  private static List<String> incompleteWith(List<String> incomplete, String item) {
    if (item == null || item.isBlank() || incomplete.contains(item)) {
      return incomplete;
    }
    List<String> out = new ArrayList<>(incomplete);
    out.add(item);
    return List.copyOf(out);
  }

  private static List<String> risksWith(List<String> risks, String item) {
    if (item == null || item.isBlank() || risks.contains(item)) {
      return risks;
    }
    List<String> out = new ArrayList<>(risks);
    out.add(item);
    return List.copyOf(out);
  }

  private static GoalDonenessDecision decision(
      GoalDonenessState state,
      RunContinuationDecision recommendedDecision,
      String reason,
      String goal,
      List<String> completed,
      List<String> incomplete,
      List<String> evidence,
      List<String> risks,
      boolean userInputRequired,
      boolean anotherModelProviderMightHelp) {
    return new GoalDonenessDecision(
        state,
        recommendedDecision,
        reason,
        goal,
        completed,
        incomplete,
        evidence,
        risks,
        userInputRequired,
        anotherModelProviderMightHelp);
  }

  private static String goal(String runId, String goalSummary) {
    if (goalSummary != null && !goalSummary.isBlank()) {
      return goalSummary.strip();
    }
    return runId == null || runId.isBlank()
        ? "run-level completion proof from final validation report"
        : "run-level completion proof from final validation report for run " + runId;
  }

  private static String status(VerificationEvidenceDTO row) {
    return row == null || row.status() == null || row.status().isBlank()
        ? "UNKNOWN"
        : row.status().toUpperCase();
  }

  private static String label(VerificationEvidenceDTO row) {
    return row == null || row.label() == null || row.label().isBlank() ? "Evidence" : row.label();
  }

  private static String label(VerificationEvidenceDTO row, String fallbackId) {
    if (row != null && row.label() != null && !row.label().isBlank()) {
      return row.label();
    }
    return fallbackId == null || fallbackId.isBlank() ? "Evidence" : fallbackId;
  }

  private static String evidenceLine(VerificationEvidenceDTO row) {
    String line = label(row) + "=" + status(row);
    if (row.source() != null && !row.source().isBlank()) {
      line += " (" + row.source() + ")";
    }
    if (row.summary() != null && !row.summary().isBlank()) {
      line += ": " + row.summary();
    }
    return line;
  }
}
