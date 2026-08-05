package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

import org.junit.jupiter.api.Test;

class ValidationReportGoalProofEvaluatorTest {

  private final ValidationReportGoalProofEvaluator evaluator =
      new ValidationReportGoalProofEvaluator();

  @Test
  void provesDoneOnlyWhenFinalReportHasPassingCompletionEvidence() {
    var decision = evaluator.evaluate("run-1", PipelineResult.PASSED, passingLedger());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.DONE);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_DONE);
    assertThat(decision.reason()).contains("proves");
    assertThat(decision.recommendedNextStep()).contains("No further work");
    assertThat(decision.incompleteItems()).isEmpty();
    assertThat(decision.anotherModelProviderMightHelp()).isFalse();
  }

  @Test
  void preservesExplicitRunGoalInFinalProof() {
    var decision =
        evaluator.evaluate(
            "run-1",
            "run goal: add billing export with CSV download",
            PipelineResult.PASSED,
            passingLedger());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.DONE);
    assertThat(decision.goalSummary()).isEqualTo("run goal: add billing export with CSV download");
  }

  @Test
  void keepsCompletionUnprovenWhenConcreteTestsAreMissing() {
    var decision =
        evaluator.evaluate(
            "run-2",
            PipelineResult.PASSED,
            List.of(
                row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
                row("build", "Build", "PASS", "DETERMINISTIC"),
                row("tests", "Tests", "UNKNOWN", "DETERMINISTIC"),
                row("reviewer", "Reviewer", "PASS", "LLM_REVIEWER"),
                row("report-issues", "Report issues", "PASS", "HYBRID"),
                row("spec-adherence", "Spec adherence", "PASS", "LLM_ASSESSMENT")));

    assertThat(decision.state()).isEqualTo(GoalDonenessState.UNCERTAIN);
    assertThat(decision.recommendedDecision())
        .isEqualTo(RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER);
    assertThat(decision.recommendedNextStep()).contains("Continue with the same model/provider");
    assertThat(decision.incompleteItems()).contains("Tests is unknown");
    assertThat(decision.risks()).contains("Tests is unknown");
  }

  @Test
  void deterministicWorkspaceSuiteCannotProveProductLevelCompletion() {
    List<VerificationEvidenceDTO> ledger =
        passingLedger().stream()
            .map(
                row ->
                    "tests".equals(row.id())
                        ? row("tests", "Tests", "PASS", "DETERMINISTIC_WORKSPACE_SUITE", 23, 23)
                        : row)
            .toList();

    var decision = evaluator.evaluate("run-workspace-only", PipelineResult.PASSED, ledger);

    assertThat(decision.state()).isEqualTo(GoalDonenessState.UNCERTAIN);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(decision.recommendedNextStep())
        .contains("independent verifier", "do not rerun code generation");
    assertThat(decision.incompleteItems())
        .contains(
            "23/23 workspace tests passed; independent functional verification is unavailable");
    assertThat(decision.completedItems()).contains("Workspace test suite passed (23/23)");
    assertThat(decision.completedItems()).doesNotContain("Tests passed");
    assertThat(decision.anotherModelProviderMightHelp()).isFalse();
    assertThat(decision.independentProofGapOnly())
        .as("the only remaining gap is the independent-functional-scope gap")
        .isTrue();
  }

  @Test
  void explicitlyNotApplicableEvidenceDoesNotBlockTheSoleIndependentGapState() {
    // 2026-08-05 live residual: a LIBRARY/CLI run's endpoint-coverage row carries status "N/A"
    // (V367 runtime-surface applicability). Declared-not-measurable evidence is not a missing
    // proof — it must not demote the sole-independent-gap advisory state to a generic UNCERTAIN.
    List<VerificationEvidenceDTO> ledger =
        new java.util.ArrayList<>(
            passingLedger().stream()
                .map(
                    row ->
                        "tests".equals(row.id())
                            ? row("tests", "Tests", "PASS", "DETERMINISTIC_WORKSPACE_SUITE", 10, 10)
                            : row)
                .toList());
    ledger.add(row("endpoint-coverage", "Endpoint coverage", "N/A", "NOT_APPLICABLE"));

    var decision = evaluator.evaluate("run-na-endpoint", PipelineResult.PASSED, ledger);

    assertThat(decision.state()).isEqualTo(GoalDonenessState.UNCERTAIN);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(decision.independentProofGapOnly()).isTrue();
    assertThat(decision.incompleteItems()).doesNotContain("Endpoint coverage is n/a");
  }

  @Test
  void independentProofGapOnlyIsFalseWheneverAnyOtherGapOrOutcomeExists() {
    // Mixed gaps: workspace-scope gap plus an unknown reviewer — not the only gap.
    List<VerificationEvidenceDTO> mixed =
        passingLedger().stream()
            .map(
                row -> {
                  if ("tests".equals(row.id())) {
                    return row("tests", "Tests", "PASS", "DETERMINISTIC_WORKSPACE_SUITE", 23, 23);
                  }
                  if ("reviewer".equals(row.id())) {
                    return row("reviewer", "Reviewer", "UNKNOWN", "LLM_REVIEWER");
                  }
                  return row;
                })
            .toList();
    assertThat(
            evaluator
                .evaluate("run-gap-mixed", PipelineResult.PASSED, mixed)
                .independentProofGapOnly())
        .isFalse();

    // Fully proven run: no gap at all.
    assertThat(
            evaluator
                .evaluate("run-gap-done", PipelineResult.PASSED, passingLedger())
                .independentProofGapOnly())
        .isFalse();

    // Failed required evidence: a real defect, not the advisory scope gap.
    List<VerificationEvidenceDTO> failed =
        passingLedger().stream()
            .map(
                row ->
                    "build".equals(row.id()) ? row("build", "Build", "FAIL", "DETERMINISTIC") : row)
            .toList();
    assertThat(
            evaluator
                .evaluate("run-gap-failed", PipelineResult.PASSED, failed)
                .independentProofGapOnly())
        .isFalse();

    // Missing ledger: unproven for a different reason.
    assertThat(
            evaluator
                .evaluate("run-gap-empty", PipelineResult.PASSED, List.of())
                .independentProofGapOnly())
        .isFalse();
  }

  @Test
  void legacyExecutionVerifiedScopeGapAlsoMarksIndependentProofGapOnly() {
    List<VerificationEvidenceDTO> ledger =
        passingLedger().stream()
            .map(
                row ->
                    "tests".equals(row.id())
                        ? row("tests", "Tests", "PASS", "DETERMINISTIC_EXECUTION", 6, 6)
                        : row)
            .toList();

    var decision = evaluator.evaluate("run-legacy-gap", PipelineResult.PASSED, ledger);

    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(decision.independentProofGapOnly()).isTrue();
  }

  @Test
  void legacyDeterministicExecutionDoesNotClaimWorkspaceProvenance() {
    List<VerificationEvidenceDTO> ledger =
        passingLedger().stream()
            .map(
                row ->
                    "tests".equals(row.id())
                        ? row("tests", "Tests", "PASS", "DETERMINISTIC_EXECUTION", 6, 6)
                        : row)
            .toList();

    var decision = evaluator.evaluate("run-legacy-execution", PipelineResult.PASSED, ledger);

    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(decision.reason()).contains("execution-verified tests").doesNotContain("workspace");
    assertThat(decision.completedItems())
        .contains("Execution-verified test suite passed (6/6)")
        .noneMatch(item -> item.contains("Workspace"));
    assertThat(decision.incompleteItems())
        .contains(
            "6/6 execution-verified tests passed; independent functional scope is unavailable")
        .noneMatch(item -> item.contains("workspace"));
  }

  @Test
  void workspaceScopeGapDoesNotMaskOtherUnprovenEvidence() {
    List<VerificationEvidenceDTO> ledger =
        passingLedger().stream()
            .map(
                row -> {
                  if ("tests".equals(row.id())) {
                    return row("tests", "Tests", "PASS", "DETERMINISTIC_WORKSPACE_SUITE", 23, 23);
                  }
                  if ("reviewer".equals(row.id())) {
                    return row("reviewer", "Reviewer", "UNKNOWN", "LLM_REVIEWER");
                  }
                  return row;
                })
            .toList();

    var decision = evaluator.evaluate("run-mixed-gaps", PipelineResult.PASSED, ledger);

    assertThat(decision.recommendedDecision())
        .isEqualTo(RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER);
    assertThat(decision.reason())
        .isEqualTo("final validation report does not prove run completion");
    assertThat(decision.incompleteItems())
        .contains(
            "23/23 workspace tests passed; independent functional verification is unavailable",
            "Reviewer is unknown");
  }

  @Test
  void workspaceScopeGapDoesNotMaskFailedRequiredEvidence() {
    List<VerificationEvidenceDTO> ledger =
        passingLedger().stream()
            .map(
                row -> {
                  if ("tests".equals(row.id())) {
                    return row("tests", "Tests", "PASS", "DETERMINISTIC_WORKSPACE_SUITE", 23, 23);
                  }
                  if ("build".equals(row.id())) {
                    return row("build", "Build", "FAIL", "DETERMINISTIC");
                  }
                  return row;
                })
            .toList();

    var decision = evaluator.evaluate("run-workspace-failed", PipelineResult.PASSED, ledger);

    assertThat(decision.state()).isEqualTo(GoalDonenessState.FAILED);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_FAILED);
    assertThat(decision.incompleteItems())
        .contains(
            "23/23 workspace tests passed; independent functional verification is unavailable",
            "Build is fail");
  }

  @Test
  void exposesTypedReferencesToExactLedgerProvenanceAndArtifacts() {
    List<VerificationEvidenceDTO> ledger =
        List.of(
            row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
            row("build", "Build", "FAIL", "DETERMINISTIC"),
            row("tests", "Tests", "PASS", "DETERMINISTIC_WORKSPACE_SUITE"),
            row("reviewer", "Reviewer", "UNKNOWN", "LLM_REVIEWER"),
            row("report-issues", "Report issues", "PASS", "HYBRID"),
            row("spec-adherence", "Spec adherence", "WARN", "LLM_ASSESSMENT"));

    var references = evaluator.evidenceReferences("COMPLETE", PipelineResult.FAILED, ledger);

    assertThat(references)
        .extracting(reference -> reference.evidenceId() + ":" + reference.proofStatus())
        .containsExactly(
            "run-lifecycle:PROVEN",
            "pipeline-result:BLOCKING",
            "code-delivered:PROVEN",
            "build:BLOCKING",
            "tests:UNPROVEN",
            "reviewer:UNPROVEN",
            "report-issues:PROVEN",
            "spec-adherence:BLOCKING");
    assertThat(references.get(3).source()).isEqualTo("DETERMINISTIC");
    assertThat(references.get(3).confidence()).isEqualTo("HIGH");
    assertThat(references.get(3).artifact()).isEqualTo("artifact");
  }

  @Test
  void missingRequiredRowsBecomeExplicitUnprovenReferencesWithoutInventedArtifacts() {
    var references = evaluator.evidenceReferences(null, null, List.of());

    assertThat(references)
        .extracting(reference -> reference.evidenceId() + ":" + reference.proofStatus())
        .containsExactly(
            "pipeline-result:UNPROVEN",
            "code-delivered:UNPROVEN",
            "build:UNPROVEN",
            "tests:UNPROVEN",
            "reviewer:UNPROVEN",
            "report-issues:UNPROVEN",
            "spec-adherence:UNPROVEN");
    assertThat(references)
        .filteredOn(reference -> !"pipeline-result".equals(reference.evidenceId()))
        .allSatisfy(reference -> assertThat(reference.artifact()).isNull());
  }

  @Test
  void terminalRunLifecycleIsAHighConfidenceBlockingReference() {
    var references =
        evaluator.evidenceReferences("CANCELLED", PipelineResult.PASSED, passingLedger());

    assertThat(references.getFirst().evidenceId()).isEqualTo("run-lifecycle");
    assertThat(references.getFirst().proofStatus()).isEqualTo("BLOCKING");
    assertThat(references.getFirst().source()).isEqualTo("DURABLE_RUN_STATE");
    assertThat(references.getFirst().artifact()).isEqualTo("pipeline_run.status");
  }

  @Test
  void passingLedgerCannotProveDoneBeforeRunLifecycleIsTerminal() {
    var decision =
        evaluator.evaluateForRun(
            "run-validating",
            "validating run",
            "VALIDATING",
            PipelineResult.PASSED,
            passingLedger());
    var references =
        evaluator.evidenceReferences("VALIDATING", PipelineResult.PASSED, passingLedger());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.UNCERTAIN);
    assertThat(decision.reason()).contains("not yet durable");
    assertThat(references.getFirst().proofStatus()).isEqualTo("UNPROVEN");
  }

  @Test
  void cancelledRunCannotBeReclassifiedDoneByAStalePassingReport() {
    var decision =
        evaluator.evaluateForRun(
            "6785219c-03b9-4f20-af67-c97781422e50",
            "cancelled run",
            "CANCELLED",
            PipelineResult.PASSED,
            passingLedger());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.FAILED);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_FAILED);
    assertThat(decision.reason()).contains("CANCELLED", "cannot certify completion");
    assertThat(decision.incompleteItems()).contains("run lifecycle is cancelled");
  }

  @Test
  void failsWhenFinalReportContainsFailedCompletionEvidence() {
    var decision =
        evaluator.evaluate(
            "run-3",
            PipelineResult.FAILED,
            List.of(
                row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
                row("build", "Build", "FAIL", "DETERMINISTIC"),
                row("tests", "Tests", "UNKNOWN", "DETERMINISTIC"),
                row("reviewer", "Reviewer", "PASS", "LLM_REVIEWER"),
                row("report-issues", "Report issues", "FAIL", "HYBRID"),
                row("spec-adherence", "Spec adherence", "PASS", "LLM_ASSESSMENT")));

    assertThat(decision.state()).isEqualTo(GoalDonenessState.FAILED);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_FAILED);
    assertThat(decision.reason()).contains("failed completion evidence");
    assertThat(decision.recommendedNextStep()).contains("alternate model/provider");
    assertThat(decision.anotherModelProviderMightHelp()).isTrue();
  }

  @Test
  void softReportConcernsDoNotTurnPassedHardEvidenceIntoStopFailed() {
    var decision =
        evaluator.evaluate(
            "run-soft-report-gaps",
            PipelineResult.PASSED,
            List.of(
                row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
                row("build", "Build", "PASS", "DETERMINISTIC"),
                row("tests", "Tests", "PASS", "DETERMINISTIC_FUNCTIONAL"),
                row("reviewer", "Reviewer", "PASS", "LLM_REVIEWER"),
                row("report-issues", "Report issues", "FAIL", "HYBRID"),
                row("spec-adherence", "Spec adherence", "FAIL", "LLM_ASSESSMENT"),
                row(
                    "test-coverage-judgment",
                    "Test coverage judgment",
                    "UNKNOWN",
                    "LLM_ASSESSMENT"),
                row("trust-cap", "Trust cap", "UNKNOWN", "INTERNAL_FORMULA"),
                row("score-summary", "Internal score", "PASS", "INTERNAL_FORMULA")));

    assertThat(decision.state()).isEqualTo(GoalDonenessState.UNCERTAIN);
    assertThat(decision.recommendedDecision())
        .isEqualTo(RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER);
    assertThat(decision.reason()).contains("does not prove run completion");
    assertThat(decision.incompleteItems())
        .contains("Report issues is fail", "Spec adherence is fail")
        .doesNotContain("Test coverage judgment is unknown", "Trust cap is unknown");
    assertThat(decision.anotherModelProviderMightHelp()).isFalse();
  }

  @Test
  void reviewerUnresolvedIsLowQualityFailureThatCanEscalate() {
    var decision =
        evaluator.evaluate(
            "run-reviewer-warn",
            PipelineResult.NEEDS_ATTENTION,
            List.of(
                row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
                row("build", "Build", "PASS", "DETERMINISTIC"),
                row("tests", "Tests", "PASS", "DETERMINISTIC_FUNCTIONAL"),
                row("reviewer", "Reviewer", "WARN", "LLM_REVIEWER"),
                row("report-issues", "Report issues", "PASS", "HYBRID"),
                row("spec-adherence", "Spec adherence", "PASS", "LLM_ASSESSMENT")));

    assertThat(decision.state()).isEqualTo(GoalDonenessState.FAILED);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_FAILED);
    assertThat(decision.reason()).contains("reviewer low-quality evidence");
    assertThat(decision.incompleteItems())
        .contains("reviewer found unresolved or failed implementation quality evidence");
    assertThat(decision.risks())
        .contains("reviewer low-quality evidence may require model/provider escalation");
    assertThat(decision.anotherModelProviderMightHelp()).isTrue();
  }

  @Test
  void reviewerFailedIsLowQualityFailureThatCanEscalate() {
    var decision =
        evaluator.evaluate(
            "run-reviewer-failed",
            PipelineResult.FAILED,
            List.of(
                row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
                row("build", "Build", "PASS", "DETERMINISTIC"),
                row("tests", "Tests", "PASS", "DETERMINISTIC_FUNCTIONAL"),
                row("reviewer", "Reviewer", "FAIL", "LLM_REVIEWER"),
                row("report-issues", "Report issues", "PASS", "HYBRID"),
                row("spec-adherence", "Spec adherence", "PASS", "LLM_ASSESSMENT")));

    assertThat(decision.state()).isEqualTo(GoalDonenessState.FAILED);
    assertThat(decision.reason()).contains("reviewer low-quality evidence");
    assertThat(decision.recommendedNextStep()).contains("alternate model/provider");
    assertThat(decision.anotherModelProviderMightHelp()).isTrue();
  }

  @Test
  void treatsMissingSpecAdherenceAsUnprovenRatherThanDone() {
    List<VerificationEvidenceDTO> ledger =
        passingLedger().stream().filter(row -> !"spec-adherence".equals(row.id())).toList();

    var decision = evaluator.evaluate("run-4", PipelineResult.PASSED, ledger);

    assertThat(decision.state()).isEqualTo(GoalDonenessState.UNCERTAIN);
    assertThat(decision.incompleteItems()).contains("spec adherence evidence unavailable");
  }

  @Test
  void optionalUnknownEvidenceDoesNotInvalidateAnOtherwiseProvenRun() {
    var decision =
        evaluator.evaluate(
            "run-static-web",
            PipelineResult.PASSED,
            List.of(
                row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
                row("build", "Build", "PASS", "DETERMINISTIC"),
                row("tests", "Tests", "PASS", "DETERMINISTIC_FUNCTIONAL"),
                row("reviewer", "Reviewer", "PASS", "LLM_REVIEWER"),
                row("report-issues", "Report issues", "PASS", "HYBRID"),
                row("spec-adherence", "Spec adherence", "PASS", "LLM_ASSESSMENT"),
                row("endpoint-coverage", "Endpoint coverage", "UNKNOWN", "LLM_ASSISTED_COUNT"),
                row("test-coverage-judgment", "Test coverage judgment", "PASS", "LLM_ASSESSMENT")));

    assertThat(decision.state()).isEqualTo(GoalDonenessState.DONE);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_DONE);
    assertThat(decision.incompleteItems()).isEmpty();
  }

  private static List<VerificationEvidenceDTO> passingLedger() {
    return List.of(
        row("code-delivered", "Code delivered", "PASS", "DETERMINISTIC"),
        row("build", "Build", "PASS", "DETERMINISTIC"),
        row("tests", "Tests", "PASS", "DETERMINISTIC_FUNCTIONAL"),
        row("reviewer", "Reviewer", "PASS", "LLM_REVIEWER"),
        row("report-issues", "Report issues", "PASS", "HYBRID"),
        row("spec-adherence", "Spec adherence", "PASS", "LLM_ASSESSMENT"),
        row("endpoint-coverage", "Endpoint coverage", "PASS", "LLM_ASSISTED_COUNT"),
        row("test-coverage-judgment", "Test coverage judgment", "PASS", "LLM_ASSESSMENT"),
        row("score-summary", "Internal score", "PASS", "INTERNAL_FORMULA"));
  }

  private static VerificationEvidenceDTO row(
      String id, String label, String status, String source) {
    return row(id, label, status, source, null, null);
  }

  private static VerificationEvidenceDTO row(
      String id, String label, String status, String source, Integer observed, Integer total) {
    return new VerificationEvidenceDTO(
        id,
        label,
        "CATEGORY",
        status,
        source,
        "HIGH",
        "a".repeat(64),
        label + " claim",
        label + " summary",
        "artifact",
        observed,
        total);
  }
}
