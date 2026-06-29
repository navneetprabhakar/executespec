package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.describeReviewerFixes;
import static com.navneet.executespec.integration.RunPipelineSupport.listWorkspaceFiles;
import static com.navneet.executespec.integration.RunPipelineSupport.mapTaskStatusToV1;
import static com.navneet.executespec.integration.RunPipelineSupport.providerForAccuracy;
import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
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
  private final com.navneet.executespec.smoketest.WorkspaceTestRunner workspaceTestRunner;
  private final com.navneet.executespec.deployment.preview.verify.PreviewVerifier previewVerifier;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;

  java.util.Optional<ValidationReport> generateValidationReport(
      String runId, ExecutionResult execResult, ReviewerResult reviewResult, Plan plan) {
    return generateValidationReport(runId, execResult, reviewResult, plan, java.util.List.of());
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
    try {
      var run = runRepo.findById(runId).orElse(null);
      if (run == null) return java.util.Optional.empty();
      var tasks = taskRepo.findByRunId(runId);

      // Enumerate files actually produced by the run so `filesGenerated` /
      // `languages` on the final report reflect reality instead of 0/empty.
      Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
      List<String> allFiles = listWorkspaceFiles(workspace);

      List<WorkerReport> workerReports = new java.util.ArrayList<>();
      boolean firstReport = true;
      if (execResult != null) {
        for (TaskOutcome o : execResult.perTask()) {
          WorkerReport wr = new WorkerReport();
          wr.setTaskId(o.taskId());
          wr.setStatus(mapTaskStatusToV1(o.status()));
          wr.setSummary(o.taskTitle());
          // Attribute all workspace files to the first task so `countFilesGenerated`
          // + `extractLanguages` have real data (V2 doesn't track per-task authorship).
          wr.setFilesCreated(firstReport ? allFiles : List.of());
          wr.setFilesModified(List.of());
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
          wr.setFilesCreated(firstReport ? allFiles : List.of());
          wr.setFilesModified(List.of());
          wr.setFilesRead(List.of());
          workerReports.add(wr);
          firstReport = false;
        }
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
        review.setVerdict(ReviewVerdict.APPROVED);
        review.setSummary("Review did not run.");
        review.setIssues(List.of());
      }

      // Run accuracy assessment — V1 does this inside ReviewerAgentV2 phase 3,
      // but V2's ReviewerAgent skips it, so the ValidationReport was coming
      // back with accuracyScore=null. Call it here so the report gets populated.
      try {
        // RL-2026-05-041 (audit #19) — no OPENAI→ANTHROPIC reroute. AccuracyAssessmentService runs
        // through FallbackLlmRouter.callWithFallbackTracked, which routes OpenAI via
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
                List.of());
        review.setAccuracyReport(accuracy);
      } catch (Exception e) {
        log.warn("RunOrchestrator run={} accuracy assessment failed: {}", runId, e.getMessage());
        // leave accuracyReport null — ValidationReportService treats null as "not assessed"
      }

      CompileResult compile =
          (reviewResult != null
                  && reviewResult.finalBuildPassed() != null
                  && reviewResult.finalBuildPassed())
              ? CompileResult.passed(1)
              : CompileResult.failed(
                  1,
                  reviewResult == null
                      ? "n/a"
                      : (reviewResult.finalBuildOutput() == null
                          ? ""
                          : reviewResult.finalBuildOutput()));

      // V2's ReviewerAgent doesn't emit structured ReviewIssues — it just returns
      // a verdict enum + summary text. That leaves ReviewScoringService with an empty
      // issues list, so every quality dimension flat-lined at the anchor regardless
      // of what actually happened. Synthesise issues from the signals we DO have
      // (build pass/fail, reviewer verdict, accuracy report) so the dimensions
      // actually move when things go wrong.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> synthIssues =
          new java.util.ArrayList<>(
              synthesiseReviewIssues(reviewResult, compile, review.getAccuracyReport()));
      if (extraIssues != null && !extraIssues.isEmpty()) {
        synthIssues.addAll(extraIssues);
      }
      review.setIssues(synthIssues);

      // RL-2026-05-001 — run the worker-written tests in the workspace and feed the REAL
      // passed/total into the verifiedScore TESTS slot, instead of an LLM coverage guess. The
      // command is resolver-derived (not worker text), validated by ShellSandboxPolicy, and run in
      // the cleanroom container where one exists. Empty → the scorer falls back to LLM coverage, so
      // this can only ever make the TESTS slot more honest, never break report generation.
      com.navneet.executespec.testgen.model.TestExecutionSummary testSummary = null;
      if (featureFlagService.isEnabled("scoring.real-test-exec")) {
        try {
          Path testWorkspace =
              Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
          testSummary = workspaceTestRunner.runTests(runId, testWorkspace).orElse(null);
        } catch (Exception e) {
          log.warn(
              "RunOrchestrator run={} workspace test execution failed (fallback to coverage): {}",
              runId,
              e.getMessage());
        }
      }
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
              run, workerReports, review, compile, tasks, testSummary, List.of(), bootVerification);
      // RL-2026-05-041 (audit #19 + E#37) — the old OPENAI→ANTHROPIC reroute (which mutated
      // run.aiProvider in-memory around this call) is removed. ValidationReportGenerator runs
      // through FallbackLlmRouter.callWithFallbackTracked, which dispatches OpenAI via
      // OpenAiNativeToolClient — NOT Spring AI's OpenAiChatModel. The Spring AI `extra_body` 400 is
      // structurally impossible on that path (it's the bug the native client was built to bypass),
      // so the reroute guarded a code path that isn't taken for OpenAI while introducing a
      // billing-attribution hazard (any reader of run.aiProvider during the swap saw the wrong
      // provider). The report now generates on the run's actual provider, entity untouched.
      java.util.Optional<ValidationReport> savedReport =
          validationReportService.generateAndPersist(runId, input);
      log.info("RunOrchestrator run={} validation report generated", runId);
      return savedReport;
    } catch (Exception e) {
      log.warn("RunOrchestrator run={} validation report failed: {}", runId, e.getMessage(), e);
      return java.util.Optional.empty();
    }
  }

  /**
   * Synthesise {@code ReviewIssue}s from the signals V2's reviewer doesn't emit structurally (build
   * pass/fail, reviewer verdict, accuracy report), so {@code ReviewScoringService}'s quality
   * dimensions actually move instead of flat-lining at the anchor.
   *
   * <p>Severity/category mapping:
   *
   * <ul>
   *   <li>Build failure → CRITICAL × 2 (LOGIC_ERROR + DEPLOYABILITY) → hits code+arch+perf
   *   <li>Reviewer UNRESOLVED_ISSUES → CRITICAL LOGIC_ERROR
   *   <li>Reviewer REVIEW_FAILED → MINOR ERROR_HANDLING
   *   <li>Accuracy missingRequirements → CRITICAL MISSING_FEATURE (one per item, capped at 5)
   *   <li>Accuracy partialRequirements → MINOR SPEC_MISMATCH (one per item, capped at 5)
   *   <li>Accuracy endpointCoverage.missing → CRITICAL MISSING_ENDPOINT (capped at 5)
   * </ul>
   */
  private static java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue>
      synthesiseReviewIssues(
          ReviewerResult reviewResult,
          com.navneet.executespec.report.model.CompileResult compile,
          com.navneet.executespec.reviewer.model.AccuracyReport accuracy) {
    java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> out =
        new java.util.ArrayList<>();

    boolean buildFailed =
        (compile != null && !compile.passed() && !compile.skipped())
            || (reviewResult != null && Boolean.FALSE.equals(reviewResult.finalBuildPassed()));
    if (buildFailed) {
      out.add(
          com.navneet.executespec.reviewer.model.ReviewIssue.builder()
              .id("SYNTH-BUILD-1")
              .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
              .category(com.navneet.executespec.reviewer.model.IssueCategory.LOGIC_ERROR)
              .description("Final build failed — code does not compile / package as produced")
              .autoFixed(false)
              .fixSucceeded(false)
              .build());
      out.add(
          com.navneet.executespec.reviewer.model.ReviewIssue.builder()
              .id("SYNTH-BUILD-2")
              .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
              .category(com.navneet.executespec.reviewer.model.IssueCategory.DEPLOYABILITY)
              .description("Build artifacts absent — the run cannot be deployed as-is")
              .autoFixed(false)
              .fixSucceeded(false)
              .build());
    }

    if (reviewResult != null) {
      switch (reviewResult.overall()) {
        case UNRESOLVED_ISSUES ->
            out.add(
                com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                    .id("SYNTH-REV-1")
                    .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                    .category(com.navneet.executespec.reviewer.model.IssueCategory.LOGIC_ERROR)
                    .description("Reviewer flagged unresolved issues in wiring/general pass")
                    .autoFixed(false)
                    .fixSucceeded(false)
                    .build());
        case REVIEW_FAILED ->
            out.add(
                com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                    .id("SYNTH-REV-2")
                    .severity(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR)
                    .category(com.navneet.executespec.reviewer.model.IssueCategory.ERROR_HANDLING)
                    .description("Reviewer agent errored — review did not complete")
                    .autoFixed(false)
                    .fixSucceeded(false)
                    .build());
        case FIXED_ISSUES -> {
          // 2026-05-19 — Phase 1 auto-fix surface. Emit an informational issue
          // with autoFixed=true so the Issues panel reflects that the reviewer
          // pass actually made edits (and they landed — build is green when
          // verdict is FIXED_ISSUES). Pre-2026-05-19 these flags were always
          // false even when the agent had done real work, so the UI made the
          // pass invisible. countUnfixedCritical filters SYNTH-FIX-* out of the
          // critical penalty (severity MINOR + autoFixed=true), so this is
          // visibility-only — doesn't double-count against the score.
          String summary = describeReviewerFixes(reviewResult);
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-FIX-1")
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.LOGIC_ERROR)
                  .description(summary)
                  .autoFixed(true)
                  .fixSucceeded(true)
                  .build());
        }
        default -> {
          /* CLEAN — no synthetic */
        }
      }
    }

    if (accuracy != null) {
      int cap = 5;
      if (accuracy.getMissingRequirements() != null) {
        int n = 0;
        for (String req : accuracy.getMissingRequirements()) {
          if (n++ >= cap) break;
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-ACC-MISS-" + n)
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.MISSING_FEATURE)
                  .description("Spec requirement not implemented: " + truncate(req, 180))
                  .autoFixed(false)
                  .fixSucceeded(false)
                  .build());
        }
      }
      if (accuracy.getPartialRequirements() != null) {
        int n = 0;
        for (String req : accuracy.getPartialRequirements()) {
          if (n++ >= cap) break;
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-ACC-PART-" + n)
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.SPEC_MISMATCH)
                  .description("Spec requirement only partially implemented: " + truncate(req, 180))
                  .autoFixed(false)
                  .fixSucceeded(false)
                  .build());
        }
      }
      if (accuracy.getEndpointCoverage() != null
          && accuracy.getEndpointCoverage().getMissing() != null) {
        int n = 0;
        for (String ep : accuracy.getEndpointCoverage().getMissing()) {
          if (n++ >= cap) break;
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-ACC-EP-" + n)
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.MISSING_ENDPOINT)
                  .description("Endpoint from spec not implemented: " + truncate(ep, 180))
                  .autoFixed(false)
                  .fixSucceeded(false)
                  .build());
        }
      }
    }

    return out;
  }
}
