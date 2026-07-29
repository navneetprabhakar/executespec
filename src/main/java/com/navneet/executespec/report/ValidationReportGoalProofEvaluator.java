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
import com.navneet.executespec.report.model.GoalProofEvidenceReferenceDTO;
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

  private static final Set<String> REQUIRED_HARD_PASS_IDS =
      Set.of("code-delivered", "build", "tests", "reviewer");

  private static final String REPORT_ISSUES_ID = "report-issues";

  private static final Set<String> OPTIONAL_PROOF_IDS =
      Set.of("spec-adherence", "endpoint-coverage", "test-coverage-judgment", "trust-cap");

  private static final List<String> PROOF_ORDER =
      List.of(
          "code-delivered",
          "build",
          "tests",
          "reviewer",
          REPORT_ISSUES_ID,
          "spec-adherence",
          "endpoint-coverage",
          "test-coverage-judgment",
          "trust-cap");

  public GoalDonenessDecision evaluate(
      String runId, PipelineResult pipelineResult, List<VerificationEvidenceDTO> ledger) {
    return evaluate(runId, null, pipelineResult, ledger);
  }

  /** Reconstruct typed provenance/artifact references from the same rows used by goal proof. */
  public List<GoalProofEvidenceReferenceDTO> evidenceReferences(
      String runStatus, PipelineResult pipelineResult, List<VerificationEvidenceDTO> ledger) {
    Map<String, VerificationEvidenceDTO> byId = new HashMap<>();
    if (ledger != null) {
      for (VerificationEvidenceDTO row : ledger) {
        if (row != null && row.id() != null && !row.id().isBlank()) {
          byId.put(row.id(), row);
        }
      }
    }
    List<GoalProofEvidenceReferenceDTO> references = new ArrayList<>();
    if (runStatus != null && !runStatus.isBlank()) {
      String lifecycle = runStatus.toUpperCase();
      boolean terminalFailure =
          "CANCELLED".equals(lifecycle)
              || "FAILED".equals(lifecycle)
              || "PARTIAL".equals(lifecycle)
              || "ABORTED".equals(lifecycle)
              || "REJECTED".equals(lifecycle);
      String lifecycleProof =
          terminalFailure ? "BLOCKING" : "COMPLETE".equals(lifecycle) ? "PROVEN" : "UNPROVEN";
      references.add(
          new GoalProofEvidenceReferenceDTO(
              "run-lifecycle",
              "Run lifecycle",
              lifecycleProof,
              lifecycle,
              "DURABLE_RUN_STATE",
              "HIGH",
              "pipeline_run.status",
              null,
              null));
    }
    String result = pipelineResult == null ? "UNKNOWN" : pipelineResult.name();
    String resultProof =
        pipelineResult == PipelineResult.PASSED
                || pipelineResult == PipelineResult.PASSED_WITH_FIXES
            ? "PROVEN"
            : pipelineResult == null ? "UNPROVEN" : "BLOCKING";
    references.add(
        new GoalProofEvidenceReferenceDTO(
            "pipeline-result",
            "Validation result",
            resultProof,
            result,
            "DURABLE_VALIDATION_REPORT",
            "HIGH",
            "validation_report.pipeline_result",
            null,
            null));
    for (String id : PROOF_ORDER) {
      VerificationEvidenceDTO row = byId.get(id);
      boolean required = REQUIRED_HARD_PASS_IDS.contains(id) || REPORT_ISSUES_ID.equals(id);
      if (row == null && !required && !"spec-adherence".equals(id)) continue;
      String observedStatus = status(row);
      String proofStatus = proofStatus(id, row, observedStatus, required);
      references.add(
          new GoalProofEvidenceReferenceDTO(
              id,
              label(row, id),
              proofStatus,
              observedStatus,
              row == null ? null : row.source(),
              row == null ? null : row.confidence(),
              row == null ? null : row.artifact(),
              row == null ? null : row.observed(),
              row == null ? null : row.total()));
    }
    return List.copyOf(references);
  }

  private static String proofStatus(
      String id, VerificationEvidenceDTO row, String observedStatus, boolean required) {
    if ("PASS".equals(observedStatus)) {
      return "tests".equals(id) && !trustedTestProof(row) ? "UNPROVEN" : "PROVEN";
    }
    if ("FAIL".equals(observedStatus) || "WARN".equals(observedStatus)) {
      return "BLOCKING";
    }
    return required || "spec-adherence".equals(id) ? "UNPROVEN" : "CONTEXT";
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
      if ("PASS".equalsIgnoreCase(row.status())
          && (!"tests".equals(row.id()) || trustedTestProof(row))) {
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

    for (String id : REQUIRED_HARD_PASS_IDS) {
      VerificationEvidenceDTO row = byId.get(id);
      String status = status(row);
      boolean trustedTestProof = !"tests".equals(id) || trustedTestProof(row);
      if ("PASS".equals(status) && trustedTestProof) {
        continue;
      }
      if ("tests".equals(id) && "PASS".equals(status) && !trustedTestProof) {
        status = "UNKNOWN";
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

    VerificationEvidenceDTO reportIssues = byId.get(REPORT_ISSUES_ID);
    String reportIssuesStatus = status(reportIssues);
    if (!"PASS".equals(reportIssuesStatus)) {
      hasUnproven = true;
      String item =
          label(reportIssues, REPORT_ISSUES_ID) + " is " + reportIssuesStatus.toLowerCase();
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
          risksWith(risks, "reviewer low-quality evidence may require model/provider escalation"),
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
      // Optional evidence is informative only when it actually measured something. In particular,
      // endpoint coverage is legitimately N/A for STATIC_WEB/CLI/LIBRARY output; treating its
      // UNKNOWN row as a failed proof made clean UI runs say "keep working" after completion.
      if ("UNKNOWN".equals(status)) {
        continue;
      }
      hasUnproven = true;
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
        false);
  }

  /**
   * Lifecycle-aware projection for persisted run/report APIs. A report generated before or during a
   * terminal cancellation/failure can retain useful evidence, but it cannot certify the run DONE.
   */
  public GoalDonenessDecision evaluateForRun(
      String runId,
      String goalSummary,
      String runStatus,
      PipelineResult pipelineResult,
      List<VerificationEvidenceDTO> ledger) {
    String normalized = runStatus == null ? "" : runStatus.toUpperCase();
    if ("CANCELLED".equals(normalized)
        || "FAILED".equals(normalized)
        || "PARTIAL".equals(normalized)
        || "ABORTED".equals(normalized)
        || "REJECTED".equals(normalized)) {
      return decision(
          GoalDonenessState.FAILED,
          RunContinuationDecision.STOP_FAILED,
          "run lifecycle is " + normalized + "; validation evidence cannot certify completion",
          goal(runId, goalSummary),
          List.of(),
          List.of("run lifecycle is " + normalized.toLowerCase()),
          ledger == null
              ? List.of()
              : ledger.stream()
                  .filter(java.util.Objects::nonNull)
                  .map(ValidationReportGoalProofEvaluator::evidenceLine)
                  .toList(),
          List.of("terminal run lifecycle contradicts completed-goal proof"),
          false,
          false);
    }
    if (!normalized.isBlank() && !"COMPLETE".equals(normalized)) {
      return decision(
          GoalDonenessState.UNCERTAIN,
          RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
          "run lifecycle is " + normalized + "; final completion is not yet durable",
          goal(runId, goalSummary),
          List.of(),
          List.of("run lifecycle is " + normalized.toLowerCase()),
          ledger == null
              ? List.of()
              : ledger.stream()
                  .filter(java.util.Objects::nonNull)
                  .map(ValidationReportGoalProofEvaluator::evidenceLine)
                  .toList(),
          List.of("non-terminal run lifecycle cannot prove completion"),
          false,
          false);
    }
    return evaluate(runId, goalSummary, pipelineResult, ledger);
  }

  private static boolean reviewerQualityFailed(VerificationEvidenceDTO reviewer) {
    String status = status(reviewer);
    return "WARN".equals(status) || "FAIL".equals(status);
  }

  private static boolean trustedTestProof(VerificationEvidenceDTO tests) {
    return tests != null
        && "DETERMINISTIC_FUNCTIONAL".equalsIgnoreCase(tests.source())
        && "HIGH".equalsIgnoreCase(tests.confidence());
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
