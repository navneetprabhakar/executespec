package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.mapTaskStatusToV1;
import static com.navneet.executespec.integration.RunPipelineSupport.providerForAccuracy;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.report.RunChangeEvidenceService;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.reviewer.AccuracyAssessmentService;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.reviewer.model.ReviewVerdict;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Run-end validation-report kickoff extracted from {@link RunOrchestrator} (god-class split,
 * increment 5b — audit #43). Assembles a {@link ReportInput} from the terminal V2 pipeline state
 * (per-task outcomes → {@link WorkerReport}s, reviewer verdict → {@link ReviewReport} + accuracy
 * assessment, final build → {@link CompileResult}, real workspace test execution, the verifyBoot
 * verdict) and hands it to {@link ValidationReportService} to compute + persist the verifiedScore.
 * Also synthesises {@code ReviewIssue}s from the signals V2's reviewer doesn't emit structurally,
 * so the quality dimensions actually move. Behaviour byte-identical to the originals.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RunValidationKickoff {

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final AgentProperties agentProps;
  private final ModelResolver modelResolver;
  private final AccuracyAssessmentService accuracyAssessmentService;
  private final ValidationReportService validationReportService;
  private final RunChangeEvidenceService runChangeEvidenceService;
  private final com.navneet.executespec.smoketest.WorkspaceTestRunner workspaceTestRunner;
  private final com.navneet.executespec.deployment.preview.verify.PreviewVerifier previewVerifier;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;
  private final RunLlmOperationLifecycleService operationLifecycle;
  private final CorrectionWorkspaceTransaction workspaceGenerationService;
  private final WorkflowConfigEvidenceInspector workflowConfigEvidenceInspector;
  private final DeterministicHttpRequirementVerifier deterministicHttpRequirementVerifier;
  private final BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  private final PerkyMessenger perky;
  private final RunValidationFailureReporter failureReporter;

  void refreshRunGoalDecisionAfterTerminal(String runId) {
    validationReportService.refreshRunGoalDecisionAfterTerminal(runId);
  }

  java.util.Optional<ValidationReport> generateValidationReport(
      String runId, ExecutionResult execResult, ReviewerResult reviewResult, Plan plan) {
    return generateValidationReport(
        runId, execResult, reviewResult, plan, java.util.List.of(), null, null);
  }

  /**
   * Correction-only validation entrypoint.
   *
   * <p>A normal run keeps waiting for independently generated live functional tests. A correction
   * cannot do that: its reversible checkpoint must be accepted or rolled back before publication.
   * Execute the repository's real suite synchronously and bind its exact command/counts/provenance
   * to the already-reviewed workspace generation. Missing, red, stale, or workspace-mutating test
   * execution fails closed before a report can be published.
   */
  java.util.Optional<ValidationReport> generateCorrectionValidationReport(
      String runId,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Plan plan,
      String reviewedWorkspaceGeneration) {
    if (reviewedWorkspaceGeneration == null || reviewedWorkspaceGeneration.isBlank()) {
      return java.util.Optional.empty();
    }
    return generateValidationReport(
        runId,
        execResult,
        reviewResult,
        plan,
        java.util.List.of(),
        null,
        reviewedWorkspaceGeneration);
  }

  /**
   * RL-2026-05-037 — overload accepting extra synthetic issues (breaking-change findings from the
   * contract gate) to append to the report's issue list so they surface in the run Issues panel.
   * The 4-arg form delegates here with an empty list.
   */
  java.util.Optional<ValidationReport> generateValidationReport(
      String runId,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Plan plan,
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> extraIssues) {
    return generateValidationReport(runId, execResult, reviewResult, plan, extraIssues, null, null);
  }

  java.util.Optional<ValidationReport> generateValidationReport(
      String runId,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Plan plan,
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> extraIssues,
      String operationPhase) {
    return generateValidationReport(
        runId, execResult, reviewResult, plan, extraIssues, operationPhase, null);
  }

  private java.util.Optional<ValidationReport> generateValidationReport(
      String runId,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Plan plan,
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> extraIssues,
      String operationPhase,
      String correctionWorkspaceGeneration) {
    RunLlmOperationLifecycleService.PhaseLease operation = null;
    try {
      var run = runRepo.findById(runId).orElse(null);
      if (run == null) return java.util.Optional.empty();
      perky.start(runId, "VALIDATING");
      failureReporter.beginAttempt(runId);
      var tasks = taskRepo.findByRunId(runId);

      // Resolve the baseline-to-final Git change set. The previous implementation walked the
      // final workspace and treated every pre-existing brownfield file as generated code.
      Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
      if (brownfieldHttpContractGuard.hasBaseline(runId)) {
        brownfieldHttpContractGuard.assertCompatible(runId);
      }
      deterministicHttpRequirementVerifier.assertSatisfied(plan, run.getUserRequest(), workspace);
      List<WorkerReport> provenanceClaims = workerProvenanceClaims(execResult);
      RunChangeEvidenceService.ChangeEvidence changeEvidence =
          runChangeEvidenceService.inspect(runId, provenanceClaims);
      List<String> createdProductFiles =
          changeEvidence.productFiles().stream()
              .filter(f -> f.status().startsWith("A"))
              .map(RunChangeEvidenceService.ChangedFile::path)
              .toList();
      List<String> modifiedProductFiles =
          changeEvidence.productFiles().stream()
              .filter(f -> !f.status().startsWith("A"))
              .map(RunChangeEvidenceService.ChangedFile::path)
              .toList();

      List<WorkerReport> workerReports = new java.util.ArrayList<>();
      boolean firstReport = true;
      if (execResult != null) {
        for (TaskOutcome o : execResult.perTask()) {
          WorkerReport wr = new WorkerReport();
          wr.setTaskId(o.taskId());
          wr.setStatus(mapTaskStatusToV1(o.status()));
          wr.setSummary(o.taskTitle());
          // The report DTO predates phase provenance. Keep its count/list compatibility on the
          // first task, but source the lists only from authoritative product diff evidence.
          wr.setFilesCreated(firstReport ? createdProductFiles : List.of());
          wr.setFilesModified(firstReport ? modifiedProductFiles : List.of());
          wr.setFilesRead(List.of());
          workerReports.add(wr);
          firstReport = false;
        }
      } else {
        // Retry-validation path: execResult wasn't persisted, so reconstruct
        // minimal WorkerReports from the persisted AgentTask rows. Status mapping
        // is straight passthrough (the task rows already hold V1-style strings).
        for (var t : tasks) {
          WorkerReport wr = new WorkerReport();
          wr.setTaskId(t.getTaskId());
          wr.setStatus(t.getStatus() == null ? "COMPLETE" : t.getStatus());
          wr.setSummary(t.getTitle());
          wr.setFilesCreated(firstReport ? createdProductFiles : List.of());
          wr.setFilesModified(firstReport ? modifiedProductFiles : List.of());
          wr.setFilesRead(List.of());
          workerReports.add(wr);
          firstReport = false;
        }
      }
      if (workerReports.isEmpty()
          && (!createdProductFiles.isEmpty() || !modifiedProductFiles.isEmpty())) {
        WorkerReport evidenceOnly = new WorkerReport();
        evidenceOnly.setTaskId("RUN_CHANGE_EVIDENCE");
        evidenceOnly.setStatus("COMPLETE");
        evidenceOnly.setSummary("Authoritative baseline-to-final product diff");
        evidenceOnly.setFilesCreated(createdProductFiles);
        evidenceOnly.setFilesModified(modifiedProductFiles);
        evidenceOnly.setFilesRead(List.of());
        workerReports.add(evidenceOnly);
      }

      ReviewReport review = new ReviewReport();
      if (reviewResult != null) {
        review.setVerdict(
            switch (reviewResult.overall()) {
              case CLEAN -> ReviewVerdict.APPROVED;
              case FIXED_ISSUES -> ReviewVerdict.APPROVED_WITH_NOTES;
              case UNRESOLVED_ISSUES -> ReviewVerdict.NEEDS_FIXES;
              case REVIEW_FAILED -> ReviewVerdict.REJECTED;
            });
        review.setSummary(
            (reviewResult.generalPassSummary() == null ? "" : reviewResult.generalPassSummary())
                + (reviewResult.wiringPassSummary() == null
                    ? ""
                    : " / " + reviewResult.wiringPassSummary()));
        review.setReviewerModel(run.getReviewerModel());
        review.setIssues(List.of());
      } else {
        // RR-289 — a missing/failed reviewer is a quality gate that did NOT run. It must never be
        // recorded as an APPROVED verdict (which would add reviewer points and drop the reviewer
        // trust cap exactly when review is unknown). Persist the FAIL side of unknown so scoring
        // treats it as REVIEW_FAILED, not CLEAN.
        review.setVerdict(ReviewVerdict.REJECTED);
        review.setSummary("Review did not run — reviewer unavailable; verdict unknown.");
        review.setIssues(List.of());
      }

      // The compile signal only depends on the reviewer's final-build outcome, so build it before
      // the accuracy assessment (RR-291): the assessor must be given the deterministic build +
      // reviewer findings, never told "code passed review" while the reviewer failed.
      CompileResult compile =
          reviewResult == null || reviewResult.finalBuildPassed() == null
              ? CompileResult.skip()
              : Boolean.TRUE.equals(reviewResult.finalBuildPassed())
                  ? CompileResult.passed(1)
                  : CompileResult.failed(
                      1,
                      reviewResult.finalBuildOutput() == null
                          ? ""
                          : reviewResult.finalBuildOutput());

      // Run worker-authored tests before the LLM accuracy assessment. A passing generated suite
      // is not independent product-quality proof, so it still cannot populate the verified TESTS
      // slot. A failing executed suite is nevertheless deterministic negative evidence: the
      // produced code and its own acceptance contract disagree. Previously this ran after the
      // assessor, allowing dc6df05f to receive A/100 immediately before pytest reported 9/10.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> workspaceDiagnosticIssues =
          new java.util.ArrayList<>();
      com.navneet.executespec.smoketest.WorkspaceTestRunner.DetailedResult workspaceTestEvidence =
          null;
      String testsGenerationBefore =
          correctionWorkspaceGeneration == null
              ? null
              : workspaceGenerationService.captureGeneration(workspace);
      if (correctionWorkspaceGeneration != null
          && !correctionWorkspaceGeneration.equals(testsGenerationBefore)) {
        throw new IllegalStateException(
            "CORRECTION_TEST_GENERATION_MISMATCH: reviewed and pre-test workspace generations"
                + " differ");
      }
      // Correction promotion is a release trust boundary, not a scoring experiment. It must run
      // the suite even when the ordinary score-enrichment flag is disabled.
      if (correctionWorkspaceGeneration != null
          || featureFlagService.isEnabled("scoring.real-test-exec")) {
        try {
          Path testWorkspace =
              Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
          workspaceTestEvidence =
              workspaceTestRunner.runTestsDetailed(runId, testWorkspace).orElse(null);
          if (workspaceTestEvidence != null
              && (workspaceTestEvidence.summary().getFailed() > 0
                  || workspaceTestEvidence.summary().getErrors() > 0)) {
            workspaceDiagnosticIssues.add(
                ValidationReviewIssueSynthesizer.workspaceTestFailure(workspaceTestEvidence));
          }
        } catch (Exception e) {
          log.warn(
              "RunOrchestrator run={} supplemental workspace test execution failed: {}",
              runId,
              e.getMessage());
        }
      }

      com.navneet.executespec.testgen.model.TestExecutionSummary correctionTestSummary = null;
      if (correctionWorkspaceGeneration != null) {
        String testsGenerationAfter = workspaceGenerationService.captureGeneration(workspace);
        correctionTestSummary =
            admitCorrectionTestEvidence(
                correctionWorkspaceGeneration,
                testsGenerationBefore,
                testsGenerationAfter,
                workspaceTestEvidence);
      }

      // RR-291 — deterministic review evidence (final-build failure + reviewer verdict) that does
      // NOT depend on the accuracy report. Passing this to assess() replaces the old empty
      // List.of(), so the assessor no longer inserts "No issues found — code passed review" when
      // the
      // reviewer actually failed or flagged unresolved issues.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> deterministicReviewIssues =
          new java.util.ArrayList<>(synthesiseReviewIssues(reviewResult, compile, null));
      deterministicReviewIssues.addAll(workspaceDiagnosticIssues);

      if (operationPhase != null) {
        operation =
            operationLifecycle.claimValidation(
                runId,
                operationPhase,
                run.getAiProvider()
                    + "|"
                    + (run.getReviewerModel() == null ? "unresolved" : run.getReviewerModel()));
      }
      com.navneet.executespec.tool.ExecutionContext validationContext =
          operation == null ? null : operation.context();

      // Run accuracy assessment — V1 does this inside ReviewerAgentV2 phase 3,
      // but V2's ReviewerAgent skips it, so the ValidationReport was coming
      // back with accuracyScore=null. Call it here so the report gets populated.
      try {
        // RL-2026-05-041 (audit #19) — no OPENAI→ANTHROPIC reroute. AccuracyAssessmentService runs
        // through LlmRouter.callText, which routes OpenAI via
        // OpenAiNativeToolClient (not Spring AI's OpenAiChatModel), so the extra_body 400 can't
        // occur on this path. OpenAI runs now assess on OpenAI (correct provider attribution).
        LlmProvider provider = providerForAccuracy(run.getAiProvider());
        // 2026-05-06 — was "ReviewerAgentV2" (legacy class name). The actual reviewer is
        // ReviewerAgent in the YAML agent-tier-map. The 3-arg resolve form bypasses the
        // tier lookup so this didn't trigger a warn, but the class-name drift was real.
        ResolvedModel planningModel =
            modelResolver.resolve("ReviewerAgent", provider, AgentTier.PLANNING);
        AccuracyReport accuracy =
            accuracyAssessmentService.assess(
                runId,
                planningModel.providerName(),
                planningModel,
                run.getUserRequest() == null ? "" : run.getUserRequest(),
                plan == null ? java.util.List.of() : plan.requirements(),
                deterministicReviewIssues,
                validationContext == null ? null : validationContext.child("accuracy-assessment"));
        review.setAccuracyReport(accuracy);
      } catch (Exception e) {
        log.warn("RunOrchestrator run={} accuracy assessment failed: {}", runId, e.getMessage());
        // leave accuracyReport null — ValidationReportService treats null as "not assessed"
      }

      // V2's ReviewerAgent doesn't emit structured ReviewIssues — it just returns
      // a verdict enum + summary text. That leaves ReviewScoringService with an empty
      // issues list, so every quality dimension flat-lined at the anchor regardless
      // of what actually happened. Synthesise issues from the signals we DO have
      // (build pass/fail, reviewer verdict, accuracy report) so the dimensions
      // actually move when things go wrong.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> synthIssues =
          new java.util.ArrayList<>(
              synthesiseReviewIssues(reviewResult, compile, review.getAccuracyReport()));
      synthIssues.addAll(workspaceDiagnosticIssues);
      if (extraIssues != null && !extraIssues.isEmpty()) {
        synthIssues.addAll(extraIssues);
      }
      review.setIssues(synthIssues);

      // A freshly executed workspace suite is deterministic code-level evidence even though it is
      // not independent end-to-end product proof. Persist its exact pass/fail/error/skip counts and
      // WORKSPACE_TEST_SUITE scope so publication can distinguish a real green command from tests
      // that never ran. ValidationReportService keeps the existing workspace-suite score cap; a
      // later black-box suite still replaces this population rather than being blended into it.
      com.navneet.executespec.testgen.model.TestExecutionSummary testSummary =
          selectTestSummary(correctionTestSummary, workspaceTestEvidence);
      // RL-2026-05-066 (verifyBoot) — feed a deterministic boot verdict into the BUILD slot. Reads
      // the run-recipe + boots in the hardened sandbox; fail-open (empty → null → compile-only
      // BUILD
      // signal, today's behaviour). Flag-gated (worker.verify-boot.enabled) and inert until preview
      // Phase 0 wires the boot engine, so default runs are byte-identical.
      com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification
          bootVerification = null;
      if (featureFlagService.isEnabled(
          com.navneet.executespec.tool.VerifyBootTool.FLAG_VERIFY_BOOT)) {
        try {
          Path bootWorkspace =
              Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
          bootVerification = previewVerifier.verify(bootWorkspace, runId).orElse(null);
        } catch (Exception e) {
          log.warn(
              "RunOrchestrator run={} boot verification failed (fallback to compile signal): {}",
              runId,
              e.getMessage());
        }
      }
      ReportInput input =
          new ReportInput(
              run,
              workerReports,
              review,
              compile,
              tasks,
              testSummary,
              List.of(),
              false,
              null,
              bootVerification,
              workspaceGenerationService.captureGeneration(workspace),
              workflowConfigEvidenceInspector.inspect(workspace),
              ValidationCommandEvidenceFactory.build(reviewResult, workspace),
              ValidationCommandEvidenceFactory.tests(workspaceTestEvidence, workspace));
      // RL-2026-05-041 (audit #19 + E#37) — the old OPENAI→ANTHROPIC reroute (which mutated
      // run.aiProvider in-memory around this call) is removed. ValidationReportGenerator runs
      // through LlmRouter.callText, which dispatches OpenAI via
      // OpenAiNativeToolClient — NOT Spring AI's OpenAiChatModel. The Spring AI `extra_body` 400 is
      // structurally impossible on that path (it's the bug the native client was built to bypass),
      // so the reroute guarded a code path that isn't taken for OpenAI while introducing a
      // billing-attribution hazard (any reader of run.aiProvider during the swap saw the wrong
      // provider). The report now generates on the run's actual provider, entity untouched.
      java.util.Optional<ValidationReport> savedReport =
          validationContext == null
              ? validationReportService.generateAndPersist(runId, input)
              : validationReportService.generateAndPersist(
                  runId, input, validationContext.child("validation-report"));
      if (operation != null) {
        if (savedReport.isPresent()) {
          operationLifecycle.complete(operation);
          operation = null;
        } else {
          operationLifecycle.terminateOneShot(operation, "VALIDATION_RESULT_NOT_PERSISTED");
          operation = null;
        }
      }
      if (savedReport.isPresent()) {
        failureReporter.clear(runId);
      } else {
        failureReporter.reportUnavailable(
            runId,
            "VALIDATION_RESULT_NOT_PERSISTED",
            "Validation completed without a durable report",
            operationPhase);
      }
      log.info("RunOrchestrator run={} validation report generated", runId);
      return savedReport;
    } catch (Exception e) {
      if (operation != null) {
        try {
          operationLifecycle.terminateOneShot(operation, "VALIDATION_FAILED");
        } catch (RuntimeException terminalFailure) {
          e.addSuppressed(terminalFailure);
        }
      }
      failureReporter.report(runId, e, operationPhase);
      log.warn("RunOrchestrator run={} validation report failed: {}", runId, e.getMessage(), e);
      return java.util.Optional.empty();
    }
  }

  static com.navneet.executespec.testgen.model.TestExecutionSummary admitCorrectionTestEvidence(
      String reviewedGeneration,
      String beforeGeneration,
      String afterGeneration,
      com.navneet.executespec.smoketest.WorkspaceTestRunner.DetailedResult evidence) {
    if (reviewedGeneration == null
        || !reviewedGeneration.equals(beforeGeneration)
        || !reviewedGeneration.equals(afterGeneration)) {
      throw new IllegalStateException(
          "CORRECTION_TEST_GENERATION_MISMATCH: tests did not execute against the reviewed"
              + " workspace generation");
    }
    if (evidence == null || evidence.summary() == null) {
      throw new IllegalStateException(
          "CORRECTION_TESTS_UNAVAILABLE: no deterministic workspace test command completed");
    }
    var summary = evidence.summary();
    if (!Boolean.TRUE.equals(summary.getExecutionVerified())) {
      throw new IllegalStateException(
          "CORRECTION_TESTS_UNVERIFIED: workspace test counts lack fresh execution provenance");
    }
    if (summary.getEvidenceScope()
        != com.navneet.executespec.testgen.model.TestExecutionSummary.EvidenceScope
            .WORKSPACE_TEST_SUITE) {
      throw new IllegalStateException(
          "CORRECTION_TEST_SCOPE_INVALID: correction tests must be a workspace suite");
    }
    if (summary.getTotalTests() <= 0) {
      throw new IllegalStateException(
          "CORRECTION_TESTS_EMPTY: correction validation requires at least one real test");
    }
    if (evidence.exitCode() != 0
        || summary.getFailed() > 0
        || summary.getErrors() > 0
        || summary.getPassed() <= 0
        || summary.getSkipped() < 0
        || (long) summary.getPassed() + summary.getSkipped() != summary.getTotalTests()) {
      throw new IllegalStateException(
          "CORRECTION_TESTS_FAILED: correction validation requires every executed test to pass");
    }
    return summary;
  }

  static com.navneet.executespec.testgen.model.TestExecutionSummary selectTestSummary(
      com.navneet.executespec.testgen.model.TestExecutionSummary correctionSummary,
      com.navneet.executespec.smoketest.WorkspaceTestRunner.DetailedResult workspaceEvidence) {
    if (correctionSummary != null) return correctionSummary;
    if (workspaceEvidence != null && workspaceEvidence.summary() != null) {
      return workspaceEvidence.summary();
    }
    return com.navneet.executespec.testgen.model.TestExecutionSummary.unavailable(
        "workspace-tests-unavailable", "No execution-verified workspace test result is available");
  }

  String persistedFailureReason(String runId, String statusReason) {
    return failureReporter.persistedReasonWhenUnavailable(runId, statusReason);
  }

  String persistedDeterministicFailureCode(String runId) {
    return failureReporter.persistedDeterministicFailureCode(runId);
  }

  static String unavailableValidationTerminalStatus(String deterministicFailureCode) {
    return deterministicFailureCode == null || deterministicFailureCode.isBlank()
        ? "PARTIAL"
        : "FAILED";
  }

  private static List<WorkerReport> workerProvenanceClaims(ExecutionResult result) {
    if (result == null || result.perTask() == null) return List.of();
    java.util.regex.Pattern pathPattern =
        java.util.regex.Pattern.compile("\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    List<WorkerReport> claims = new java.util.ArrayList<>();
    for (TaskOutcome outcome : result.perTask()) {
      List<String> paths = new java.util.ArrayList<>();
      for (var worker :
          new com.navneet.executespec.engine.WorkerResult[] {
            outcome.workerOutcome(), outcome.retryWorkerOutcome()
          }) {
        if (worker == null || worker.toolCallLog() == null) continue;
        worker.toolCallLog().stream()
            .filter(line -> line != null && line.endsWith("-> OK"))
            .filter(line -> line.startsWith("writeFile(") || line.startsWith("editFile("))
            .forEach(
                line -> {
                  java.util.regex.Matcher matcher = pathPattern.matcher(line);
                  if (matcher.find()) paths.add(matcher.group(1));
                });
      }
      WorkerReport claim = new WorkerReport();
      claim.setTaskId(outcome.taskId());
      claim.setFilesCreated(paths);
      claim.setFilesModified(paths);
      claims.add(claim);
    }
    return claims;
  }

  /** Compatibility seam retained for focused provenance tests. */
  static java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> synthesiseReviewIssues(
      ReviewerResult reviewResult,
      com.navneet.executespec.report.model.CompileResult compile,
      com.navneet.executespec.reviewer.model.AccuracyReport accuracy) {
    return ValidationReviewIssueSynthesizer.synthesise(reviewResult, compile, accuracy);
  }
}
