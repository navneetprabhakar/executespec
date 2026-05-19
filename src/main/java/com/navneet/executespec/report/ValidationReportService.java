package com.navneet.executespec.report;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.report.model.ReportIssueDTO;
import com.navneet.executespec.report.model.ValidationReportDTO;
import com.navneet.executespec.report.model.ValidationReportShareDTO;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.reviewer.model.ReviewVerdict;
import com.navneet.executespec.scoring.ReviewScoringService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ValidationReportService {

  private final ValidationReportRepository repository;
  private final ValidationReportGenerator generator;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final StateStore stateStore;
  private final ReviewScoringService reviewScoringService;
  private final FeatureFlagService featureFlagService;

  /**
   * v2.5.10 — when enabled, ReviewScoringService anchors on verifiedScore (build + tests + verdict)
   * instead of the LLM accuracy score, and the 5 verified columns feed the primary UI badges.
   */
  private static final String FLAG_VERIFIED_SCORE = "scoring.v2-verified";

  /**
   * 2026-05-19 — additive scoring rebuild.
   *
   * <p>When ON (default true), {@code verifiedScore} is computed as a weighted-additive average
   * over measured signals with CODE_DELIVERED as the primary factor. When OFF, the legacy
   * multiplicative formula stays. Kept as a flag because (a) the multiplicative formula is
   * pinned by ValidationReportServiceVerifiedScoreTest, which lets us roll back without code
   * churn if customer data shows the new shape misranking real runs, and (b) the previous
   * V145/V167 thrash on the FREE-tier limit taught us to keep scoring changes reversible.
   *
   * <p>See {@link #computeVerifiedScoreAdditive} for the rationale on the weights and the
   * "code delivered" hard floor.
   */
  private static final String FLAG_ADDITIVE_SCORE = "scoring.additive-v2";

  /**
   * Main entry point — called after ReviewerAgent completes, before MergeGate. Non-blocking on
   * failure: catches all exceptions and returns Optional.empty().
   */
  public Optional<ValidationReport> generateAndPersist(String runId, ReportInput input) {
    long startTime = System.currentTimeMillis();

    try {
      // 1. Collect structured data from pipeline outputs
      List<ReportIssueDTO> issues = extractIssues(input);
      String issuesJson = objectMapper.writeValueAsString(issues);

      Set<String> languages = extractLanguages(input);
      List<String> checksPassed = extractChecksPassed(input);
      int filesGenerated = countFilesGenerated(input);
      PipelineResult pipelineResult = computeResult(issues, input);

      // 2. Call LLM to generate markdown report (with fallback if LLM unavailable)
      String reportMarkdown;
      String modelUsed = null;
      int inputTokens = 0;
      int outputTokens = 0;
      try {
        var llmResult = generator.generate(input);
        reportMarkdown = llmResult.markdown();
        modelUsed = llmResult.model();
        inputTokens = llmResult.inputTokens();
        outputTokens = llmResult.outputTokens();
      } catch (Exception llmEx) {
        log.warn(
            "[Report] LLM report generation failed for run={} — saving structured fallback: {}",
            runId,
            llmEx.getMessage());
        reportMarkdown = buildFallbackMarkdown(runId, issues, input);
      }

      // 3. Extract accuracy data from ReviewReport (Phase 3 output)
      AccuracyReport accuracyReport =
          input.review() != null ? input.review().getAccuracyReport() : null;
      Integer accuracyScore = null;
      String accuracyGrade = null;
      String accuracyJson = null;
      if (accuracyReport != null) {
        accuracyScore = accuracyReport.getSpecAdherenceScore();
        accuracyGrade = accuracyReport.getOverallAccuracyGrade();
        try {
          accuracyJson = objectMapper.writeValueAsString(accuracyReport);
        } catch (Exception e) {
          log.warn("Failed to serialize AccuracyReport for run={}: {}", runId, e.getMessage());
        }
        // Include accuracy in checks_passed if score is high
        if (accuracyScore != null && accuracyScore >= 80) {
          checksPassed.add("ACCURACY_CHECK");
        }
      }

      // 3b. v2.5.10 — verified signals (build outcome, test counts, reviewer verdict).
      // These replace the LLM-only anchor in ReviewScoringService when the
      // scoring.v2-verified flag is on, and always persist alongside the
      // legacy columns so the UI can show both and toggling the flag on
      // later is instant.
      Boolean finalBuildPassed =
          input.compileResult() != null ? input.compileResult().passed() : null;
      TestExecutionSummary ts = input.testSummary();
      int testsPassedCount = ts != null ? ts.getPassed() : 0;
      int testsTotalCount = ts != null ? ts.getTotalTests() : 0;
      String reviewerVerdict =
          mapVerdictToV2Name(input.review() != null ? input.review().getVerdict() : null);
      int unfixedCritical = countUnfixedCritical(input);
      // P1.2 — feed deterministic endpoint coverage into the verifiedScore. Pulled from the
      // accuracy report's endpointCoverage block when present (this field is a deterministic
      // count, not LLM-graded — see AccuracyAssessmentService SYSTEM_PROMPT lines 124–128).
      Integer endpointCoveragePercent =
          (accuracyReport != null && accuracyReport.getEndpointCoverage() != null)
              ? accuracyReport.getEndpointCoverage().getCoveragePercent()
              : null;
      // PR-R (2026-05-14) — read the AccuracyAssessment testCoverageLevel signal once and
      // thread it through both scorers. When testsTotal=0 this prevents the formula from
      // mechanically capping at 50 on specs that legitimately needed no tests
      // (testCoverageLevel=GOOD/COMPREHENSIVE means "coverage matches what spec implied").
      String testCoverageLevel =
          accuracyReport != null ? accuracyReport.getTestCoverageLevel() : null;
      int verifiedScore =
          computeVerifiedScore(
              finalBuildPassed,
              testsPassedCount,
              testsTotalCount,
              reviewerVerdict,
              unfixedCritical,
              endpointCoveragePercent,
              testCoverageLevel,
              filesGenerated);

      // PR-E Phase A (2026-05-13) — additive rubric companion. Fixes the verifiedScore=0
      // cliff where any single zero factor (failed build, REVIEW_FAILED) collapses the entire
      // headline. The rubric awards points dimension-by-dimension so a run that compiled
      // cleanly but had a flaky test still reads 80/100 instead of 0/100.
      int rubricScore =
          computeRubricScore(
              finalBuildPassed,
              testsPassedCount,
              testsTotalCount,
              reviewerVerdict,
              unfixedCritical,
              endpointCoveragePercent,
              testCoverageLevel);

      boolean verifiedScoreFlag = featureFlagService.isEnabled(FLAG_VERIFIED_SCORE);

      // 3c. Pilot R6 — six-dimension score derived deterministically from
      // the anchor + review issues. Under the verified flag, the anchor
      // is verifiedScore; otherwise it's the LLM-generated accuracy score
      // (legacy behaviour, preserved for rollback).
      String figmaUrl = input.run() != null ? input.run().getFigmaUrl() : null;
      ReviewScoringService.Scores scores =
          reviewScoringService.score(
              input.run(),
              input.review(),
              accuracyReport,
              verifiedScoreFlag ? Integer.valueOf(verifiedScore) : null,
              figmaUrl);

      // 4. Build and persist entity
      var report =
          ValidationReport.builder()
              .id(UUID.randomUUID().toString())
              .runId(runId)
              .filesGenerated(filesGenerated)
              .languages(String.join(",", languages))
              .pipelineResult(pipelineResult)
              .issuesJson(issuesJson)
              .checksPassed(String.join(",", checksPassed))
              .reportMarkdown(reportMarkdown)
              .modelUsed(modelUsed)
              .inputTokens(inputTokens)
              .outputTokens(outputTokens)
              .generationTimeMs(System.currentTimeMillis() - startTime)
              .generatedAfterImprovements(input.generatedAfterImprovements())
              .accuracyScore(accuracyScore)
              .accuracyGrade(accuracyGrade)
              .accuracyJson(accuracyJson)
              .codeQualityScore(scores.codeQuality())
              .testCoverageScore(scores.testCoverage())
              .architectureScore(scores.architecture())
              .securityScore(scores.security())
              .performanceScore(scores.performance())
              .designComplianceScore(scores.designCompliance())
              .finalBuildPassed(finalBuildPassed)
              .testsPassed(ts != null ? testsPassedCount : null)
              .testsTotal(ts != null ? testsTotalCount : null)
              .reviewerVerdict(reviewerVerdict)
              .verifiedScore(verifiedScore)
              .rubricScore(rubricScore)
              .previewUrl(input.previewUrl())
              .status("COMPLETE")
              .build();

      // Accumulate report tokens into run total for billing accuracy
      try {
        if (inputTokens > 0 || outputTokens > 0) {
          stateStore.addTokensToRun(runId, inputTokens, outputTokens);
        }
      } catch (Exception tokenEx) {
        log.warn("Failed to accumulate report tokens to run {}: {}", runId, tokenEx.getMessage());
      }

      // Delete any existing report for this run (rework edge case)
      repository.findByRunId(runId).ifPresent(existing -> repository.delete(existing));
      var saved = repository.save(report);

      // 5. Metrics
      meterRegistry
          .counter("executespec.report.generated", "result", pipelineResult.name())
          .increment();
      meterRegistry.counter("executespec.report.issues_caught").increment(issues.size());
      Timer.builder("executespec.report.generation_duration")
          .register(meterRegistry)
          .record(Duration.ofMillis(System.currentTimeMillis() - startTime));
      if (accuracyScore != null) {
        meterRegistry.summary("executespec.report.accuracy_score").record(accuracyScore);
      }

      log.info(
          "Validation report generated for run={} issues={} result={} accuracy={}/{} tokens={}+{}"
              + " time={}ms",
          runId,
          issues.size(),
          pipelineResult,
          accuracyScore,
          accuracyGrade,
          inputTokens,
          outputTokens,
          report.getGenerationTimeMs());

      return Optional.of(saved);

    } catch (Exception e) {
      log.error("Failed to generate validation report for run={}: {}", runId, e.getMessage(), e);
      meterRegistry
          .counter("executespec.report.errors", "error_type", e.getClass().getSimpleName())
          .increment();
      return Optional.empty();
    }
  }

  public Optional<ValidationReportDTO> getByRunId(String runId) {
    return repository.findByRunId(runId).map(this::toDTO);
  }

  public Optional<com.navneet.executespec.report.entity.ValidationReport> findEntityByRunId(
      String runId) {
    return repository.findByRunId(runId);
  }

  public Optional<ValidationReportShareDTO> getByShareToken(String shareToken) {
    return repository.findByShareToken(shareToken).map(this::toShareDTO);
  }

  public String share(String runId) {
    var report =
        repository
            .findByRunId(runId)
            .orElseThrow(() -> new IllegalArgumentException("Report not found for run " + runId));

    if (report.getShareToken() != null) {
      return report.getShareToken();
    }

    String token = generateShareToken();
    report.setShareToken(token);
    report.setSharedAt(Instant.now());
    repository.save(report);

    log.info("Validation report shared: run={} token={}", runId, token);
    meterRegistry.counter("executespec.report.shared").increment();

    return token;
  }

  // ── Private helpers ──────────────────────────────────────────────────────

  private List<ReportIssueDTO> extractIssues(ReportInput input) {
    List<ReportIssueDTO> issues = new ArrayList<>();

    // Issues from ReviewerAgentV2 (flat issue list)
    //
    // 2026-05-10 — distinguish reviewer-flagged issues from
    // accuracy-LLM-synthesised ones via the `source` field. Pre-fix every
    // issue surfaced as REVIEWER_AGENT in the dashboard panel even though
    // the SYNTH-ACC-* rows are LLM read-budget hallucinations
    // (RunOrchestrator.synthesiseReviewIssues stamps the prefix). Same
    // line of reasoning that drove the V106 verifiedScore filter
    // (CLAUDE.md P3.2): a deterministic source label lets the UI group /
    // mute the LLM-derived ones independently from real reviewer findings.
    ReviewReport review = input.review();
    if (review != null && review.getIssues() != null) {
      for (ReviewIssue ri : review.getIssues()) {
        String fixNote = null;
        if (ri.isAutoFixed()) {
          fixNote =
              ri.isFixSucceeded()
                  ? "Auto-fixed successfully"
                  : "Auto-fix failed after retry with model escalation — unresolved";
        }
        String source =
            (ri.getId() != null && ri.getId().startsWith("SYNTH-ACC-"))
                ? "ACCURACY_LLM"
                : "REVIEWER_AGENT";
        issues.add(
            new ReportIssueDTO(
                ri.getDescription(), mapSeverity(ri.getSeverity()), fixNote, source, ri.getFile()));
      }
    }

    // Note: mergeBlockers are derived from unfixed CRITICAL issues already in the
    // flat issues[] list above — no need to add them again (avoids duplication).

    // Issues from compiler
    if (input.compileResult() != null
        && !input.compileResult().passed()
        && !input.compileResult().skipped()) {
      String compilerNote =
          input.compileResult().passed()
              ? "Resolved by CompilerAgent after "
                  + input.compileResult().attempts()
                  + " compilation round(s)"
              : "Compilation failed after "
                  + input.compileResult().attempts()
                  + " attempt(s) — unresolved errors remain";
      issues.add(
          new ReportIssueDTO(
              "Compilation failed: " + truncate(input.compileResult().errorSummary(), 200),
              "WOULD_BREAK_BUILD",
              compilerNote,
              "COMPILER_AGENT",
              null));
    }

    // Issues from integration test failures — group by root cause
    if (input.testResults() != null) {
      List<TestResult> failures =
          input.testResults().stream()
              .filter(
                  tr ->
                      tr.getStatus() == TestResultStatus.FAILED
                          || tr.getStatus() == TestResultStatus.ERROR)
              .toList();

      if (!failures.isEmpty()) {
        // Group by failure signature (status code pattern) to detect shared root cause
        Map<String, List<TestResult>> grouped =
            failures.stream()
                .collect(
                    java.util.stream.Collectors.groupingBy(
                        tr -> extractFailureSignature(tr.getFailureMessage())));

        for (var entry : grouped.entrySet()) {
          List<TestResult> group = entry.getValue();
          if (group.size() >= 3) {
            // Collapse into a single root-cause issue
            String methods =
                group.stream()
                    .map(TestResult::getTestMethod)
                    .limit(3)
                    .collect(java.util.stream.Collectors.joining(", "));
            String desc =
                group.size()
                    + " integration tests failed with same root cause: "
                    + entry.getKey()
                    + " (e.g. "
                    + methods
                    + (group.size() > 3 ? ", ..." : "")
                    + ")";
            // Use suggestion from first failure that has one
            String suggestion =
                group.stream()
                    .map(TestResult::getSuggestion)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse(null);
            issues.add(
                new ReportIssueDTO(desc, "WOULD_CAUSE_BUG", suggestion, "INTEGRATION_TEST", null));
          } else {
            // Few failures — list individually
            for (TestResult tr : group) {
              String desc = "Integration test failed: " + tr.getTestMethod();
              if (tr.getFailureMessage() != null) {
                desc += " — " + truncate(tr.getFailureMessage(), 150);
              }
              issues.add(
                  new ReportIssueDTO(
                      desc, "WOULD_CAUSE_BUG", tr.getSuggestion(), "INTEGRATION_TEST", null));
            }
          }
        }
      }
    }

    return issues;
  }

  private String mapSeverity(IssueSeverity severity) {
    if (severity == null) return "WOULD_FAIL_REVIEW";
    return switch (severity) {
      case CRITICAL -> "WOULD_CAUSE_BUG";
      case MINOR -> "WOULD_FAIL_REVIEW";
    };
  }

  private Set<String> extractLanguages(ReportInput input) {
    Set<String> languages = new LinkedHashSet<>();
    if (input.workerReports() != null) {
      for (WorkerReport wr : input.workerReports()) {
        if (wr.getFilesCreated() != null) {
          wr.getFilesCreated()
              .forEach(
                  f -> {
                    String lang = detectLanguage(f);
                    if (!"Other".equals(lang)) languages.add(lang);
                  });
        }
        if (wr.getFilesModified() != null) {
          wr.getFilesModified()
              .forEach(
                  f -> {
                    String lang = detectLanguage(f);
                    if (!"Other".equals(lang)) languages.add(lang);
                  });
        }
      }
    }
    return languages;
  }

  private List<String> extractChecksPassed(ReportInput input) {
    List<String> passed = new ArrayList<>();
    if (input.compileResult() != null
        && input.compileResult().passed()
        && !input.compileResult().skipped()) {
      passed.add("COMPILATION");
    }
    if (input.review() != null) {
      // V2: check mergeBlockers (populated from unfixed CRITICAL issues)
      boolean hasBlockers =
          input.review().getMergeBlockers() != null && !input.review().getMergeBlockers().isEmpty();
      if (!hasBlockers) {
        passed.add("CODE_REVIEW");
      }
    }
    // Integration tests
    if (input.testSummary() != null && input.testSummary().getTotalTests() > 0) {
      if (input.testSummary().getFailed() == 0 && input.testSummary().getErrors() == 0) {
        passed.add("INTEGRATION_TESTS");
      }
    }
    return passed;
  }

  private int countFilesGenerated(ReportInput input) {
    int count = 0;
    if (input.workerReports() != null) {
      for (WorkerReport wr : input.workerReports()) {
        if (wr.getFilesCreated() != null) count += wr.getFilesCreated().size();
        if (wr.getFilesModified() != null) count += wr.getFilesModified().size();
      }
    }
    return count;
  }

  private PipelineResult computeResult(List<ReportIssueDTO> issues, ReportInput input) {
    PipelineResult heuristic = computeResultHeuristic(issues, input);
    // R-09 (UX audit, 2026-05-04) — reconcile against the run's
    // authoritative status_reason. Pre-fix, the heuristic above could
    // disagree with RunOrchestrator.resolveTerminalStatus() (e.g. heuristic
    // returned FAILED because a CRITICAL issue stayed in the list, but the
    // reviewer's overall verdict + final build pass meant resolveTerminalStatus
    // already wrote CLEAN_COMPLETION). Historical runs ended up with
    // status_reason=CLEAN_COMPLETION + pipelineResult=FAILED, which the
    // frontend papered over via a UI special case (`suppressReason` in
    // ValidationReportPanel.tsx). With the reconciliation below, the two
    // signals agree at write time so the UI special case can go.
    return reconcileWithRunStatus(heuristic, input);
  }

  /**
   * Original heuristic from before R-09. Kept as a private fallback so the existing test surface
   * remains valid for inputs where the run state is absent or LEGACY_UNKNOWN.
   */
  private PipelineResult computeResultHeuristic(List<ReportIssueDTO> issues, ReportInput input) {
    // Compilation failure is always FAILED
    if (input.compileResult() != null
        && !input.compileResult().passed()
        && !input.compileResult().skipped()) {
      return PipelineResult.FAILED;
    }

    if (issues.isEmpty()) return PipelineResult.PASSED;

    // Check if all review issues were auto-fixed successfully
    ReviewReport review = input.review();
    if (review != null && review.getIssues() != null && !review.getIssues().isEmpty()) {
      boolean allFixed =
          review.getIssues().stream().allMatch(i -> i.isAutoFixed() && i.isFixSucceeded());
      if (allFixed) return PipelineResult.PASSED_WITH_FIXES;

      // Any unresolved CRITICAL issues → FAILED
      boolean hasUnresolvedCritical =
          review.getIssues().stream()
              .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
              .anyMatch(i -> !i.isAutoFixed() || !i.isFixSucceeded());
      if (hasUnresolvedCritical) return PipelineResult.FAILED;
    }

    // Issues exist but not all resolved (e.g. MINOR unresolved, or test failures)
    return PipelineResult.NEEDS_ATTENTION;
  }

  /**
   * Package-private for {@link com.navneet.executespec.report.ValidationReportServiceR09Test}.
   * Direct test access without spinning up a real {@link ValidationReport} write because
   * computeResult is invoked via the persistence path which needs a live DB.
   */
  PipelineResult reconcileForTest(PipelineResult heuristic, ReportInput input) {
    return reconcileWithRunStatus(heuristic, input);
  }

  /**
   * R-09 — when the run's status_reason gives an authoritative verdict, override the heuristic.
   * Trust order: status_reason > heuristic > status fallback.
   *
   * <p>Mapping (matches the state machine in {@code RunOrchestrator.resolveTerminalStatus}):
   *
   * <ul>
   *   <li>{@code CLEAN_COMPLETION} / {@code CLEAN_WITH_INTERMEDIATE_WARNINGS} → {@code PASSED} (or
   *       {@code PASSED_WITH_FIXES} when the heuristic spotted successfully auto-fixed review
   *       issues)
   *   <li>{@code FINAL_BUILD_FAILED} / {@code ALL_TASKS_FAILED} / {@code EXECUTION_ABORTED} →
   *       {@code FAILED}
   *   <li>{@code REVIEWER_UNRESOLVED} / {@code REVIEWER_FAILED} / {@code TESTS_FAILED:N_OF_M} →
   *       {@code NEEDS_ATTENTION} (or {@code FAILED} when the heuristic already caught a CRITICAL —
   *       keep the stricter)
   *   <li>{@code LEGACY_UNKNOWN} / null → fall through to the heuristic (back-compat for
   *       pre-v2.5.10 runs and edge cases where the orchestrator didn't set a reason)
   * </ul>
   *
   * <p>When the heuristic is FAILED and the run says CLEAN_*, the run wins — the orchestrator
   * considers the reviewer's overall verdict + final build, which is a tighter signal than
   * per-issue severity. A logged warning records the drift for debugging.
   */
  private PipelineResult reconcileWithRunStatus(PipelineResult heuristic, ReportInput input) {
    if (input.run() == null) return heuristic;
    String reason = input.run().getStatusReason();
    if (reason == null) return heuristic;

    // Tests failures encode the count: TESTS_FAILED:N_OF_M
    boolean isTestsFailed = reason.startsWith("TESTS_FAILED:");

    PipelineResult fromReason =
        switch (reason) {
          case "CLEAN_COMPLETION", "CLEAN_WITH_INTERMEDIATE_WARNINGS" ->
              heuristic == PipelineResult.PASSED_WITH_FIXES
                  ? PipelineResult.PASSED_WITH_FIXES
                  : PipelineResult.PASSED;
          case "FINAL_BUILD_FAILED", "ALL_TASKS_FAILED", "EXECUTION_ABORTED" ->
              PipelineResult.FAILED;
          case "REVIEWER_UNRESOLVED",
                  "REVIEWER_FAILED",
                  "REVIEWER_INTERRUPTED_CODE_PRESERVED",
                  "RE_REVIEW_FAILED",
                  "RE_REVIEW_NO_WORKSPACE" ->
              heuristic == PipelineResult.FAILED
                  ? PipelineResult.FAILED
                  : PipelineResult.NEEDS_ATTENTION;
          case "LEGACY_UNKNOWN", "NO_REVIEW_RESULT" -> heuristic;
          default -> isTestsFailed ? PipelineResult.NEEDS_ATTENTION : heuristic;
        };

    if (fromReason != heuristic) {
      log.warn(
          "R-09 reconcile: heuristic={} but status_reason={} on run {} — using {}",
          heuristic,
          reason,
          input.run().getRunId(),
          fromReason);
    }
    return fromReason;
  }

  private ValidationReportDTO toDTO(ValidationReport entity) {
    List<ReportIssueDTO> issues = parseIssues(entity.getIssuesJson());
    List<String> languages =
        entity.getLanguages() != null && !entity.getLanguages().isBlank()
            ? Arrays.asList(entity.getLanguages().split(","))
            : List.of();
    List<String> checksPassed =
        entity.getChecksPassed() != null && !entity.getChecksPassed().isBlank()
            ? Arrays.asList(entity.getChecksPassed().split(","))
            : List.of();

    return new ValidationReportDTO(
        entity.getId(),
        entity.getRunId(),
        entity.getFilesGenerated(),
        languages,
        entity.getPipelineResult(),
        issues,
        checksPassed,
        entity.getReportMarkdown(),
        entity.getModelUsed(),
        entity.getCreatedAt(),
        issues.size(),
        countBySeverity(issues, "WOULD_BREAK_BUILD"),
        countBySeverity(issues, "WOULD_FAIL_REVIEW"),
        countBySeverity(issues, "WOULD_CAUSE_BUG"),
        countBySeverity(issues, "WOULD_FAIL_LINT"),
        entity.getShareToken() != null,
        entity.getShareToken() != null ? "/reports/shared/" + entity.getShareToken() : null,
        entity.isGeneratedAfterImprovements(),
        entity.getAccuracyScore(),
        entity.getAccuracyGrade(),
        entity.getAccuracyJson(),
        entity.getCodeQualityScore(),
        entity.getTestCoverageScore(),
        entity.getArchitectureScore(),
        entity.getSecurityScore(),
        entity.getPerformanceScore(),
        entity.getDesignComplianceScore(),
        entity.getFinalBuildPassed(),
        entity.getTestsPassed(),
        entity.getTestsTotal(),
        entity.getReviewerVerdict(),
        entity.getVerifiedScore(),
        entity.getRubricScore(),
        entity.getPreviewUrl());
  }

  private ValidationReportShareDTO toShareDTO(ValidationReport entity) {
    List<ReportIssueDTO> issues = parseIssues(entity.getIssuesJson());
    List<String> languages =
        entity.getLanguages() != null && !entity.getLanguages().isBlank()
            ? Arrays.asList(entity.getLanguages().split(","))
            : List.of();
    List<String> checksPassed =
        entity.getChecksPassed() != null && !entity.getChecksPassed().isBlank()
            ? Arrays.asList(entity.getChecksPassed().split(","))
            : List.of();

    return new ValidationReportShareDTO(
        entity.getFilesGenerated(),
        languages,
        entity.getPipelineResult(),
        issues,
        checksPassed,
        entity.getReportMarkdown(),
        entity.getCreatedAt());
  }

  private List<ReportIssueDTO> parseIssues(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      log.warn("Failed to parse issues JSON: {}", e.getMessage());
      return List.of();
    }
  }

  private int countBySeverity(List<ReportIssueDTO> issues, String severity) {
    return (int) issues.stream().filter(i -> severity.equals(i.severity())).count();
  }

  private String generateShareToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String detectLanguage(String filePath) {
    if (filePath == null) return "Other";
    if (filePath.endsWith(".java")) return "Java";
    if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) return "TypeScript";
    if (filePath.endsWith(".js") || filePath.endsWith(".jsx")) return "JavaScript";
    if (filePath.endsWith(".py")) return "Python";
    if (filePath.endsWith(".kt")) return "Kotlin";
    if (filePath.endsWith(".sql")) return "SQL";
    return "Other";
  }

  /**
   * Extract a failure signature from an error message for root-cause grouping. Groups failures that
   * share the same HTTP status code pattern, exception type, or error category so they collapse
   * into a single root-cause issue.
   */
  public static String extractFailureSignature(String failureMessage) {
    if (failureMessage == null || failureMessage.isBlank()) return "unknown failure";
    String msg = failureMessage.trim();

    // HTTP status code patterns: "expected 200 but got 404", "status 500", "HTTP 503"
    var httpPattern =
        java.util.regex.Pattern.compile(
            "(?:got|status|HTTP|responded with|returned)\\s*(\\d{3})",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    var httpMatcher = httpPattern.matcher(msg);
    if (httpMatcher.find()) {
      return "HTTP " + httpMatcher.group(1);
    }

    // Connection errors
    if (msg.toLowerCase().contains("connection refused")
        || msg.toLowerCase().contains("econnrefused")) {
      return "connection refused";
    }
    if (msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("timed out")) {
      return "timeout";
    }
    if (msg.toLowerCase().contains("socket hang up") || msg.toLowerCase().contains("econnreset")) {
      return "connection reset";
    }

    // Java exception type: "java.lang.NullPointerException: ..."
    var exceptionPattern = java.util.regex.Pattern.compile("([a-zA-Z]+(?:\\.[a-zA-Z]+)*Exception)");
    var exMatcher = exceptionPattern.matcher(msg);
    if (exMatcher.find()) {
      // Use simple name
      String full = exMatcher.group(1);
      int lastDot = full.lastIndexOf('.');
      return lastDot >= 0 ? full.substring(lastDot + 1) : full;
    }

    // Assertion patterns: "expected X but was Y" → normalize to "assertion mismatch"
    if (msg.toLowerCase().contains("expected")
        && (msg.toLowerCase().contains("but was") || msg.toLowerCase().contains("but got"))) {
      return "assertion mismatch";
    }

    // Fallback: first 60 chars, normalized
    String normalized = msg.replaceAll("\\s+", " ");
    return normalized.length() <= 60 ? normalized : normalized.substring(0, 60) + "...";
  }

  /** Minimal markdown report used when the LLM call fails, so the report is always persisted. */
  private String buildFallbackMarkdown(
      String runId, List<ReportIssueDTO> issues, ReportInput input) {
    var sb = new StringBuilder();
    sb.append("# Validation Report\n\n");
    sb.append("> _Automated quality check summary (narrative generation unavailable)._\n\n");

    if (input.compileResult() != null && !input.compileResult().skipped()) {
      sb.append("## Compilation\n");
      sb.append(input.compileResult().passed() ? "✅ Passed\n\n" : "❌ Failed\n\n");
    }

    if (input.review() != null) {
      sb.append("## Code Review\n");
      int issueCount = issues.size();
      if (issueCount == 0) {
        sb.append("✅ No issues found.\n\n");
      } else {
        sb.append("Found %d issue(s):\n\n".formatted(issueCount));
        for (ReportIssueDTO issue : issues) {
          sb.append("- **[%s]** %s".formatted(issue.severity(), issue.description()));
          if (issue.fix() != null) sb.append(" — %s".formatted(issue.fix()));
          sb.append("\n");
        }
        sb.append("\n");
      }
    }

    if (input.testSummary() != null && input.testSummary().getTotalTests() > 0) {
      var ts = input.testSummary();
      sb.append("## Integration Tests\n");
      sb.append(
          "- Total: %d, Passed: %d, Failed: %d\n\n"
              .formatted(ts.getTotalTests(), ts.getPassed(), ts.getFailed()));
    }

    return sb.toString();
  }

  // ── v2.5.10 verified score helpers ─────────────────────────────────────────

  /**
   * Deterministic score derived from the signals that actually exist at report time. Unlike the LLM
   * accuracy score this cannot hallucinate — every input is a hard fact observed by the pipeline.
   *
   * <p>Formula (R-132, greenfield §4.5): base = 40 buildBonus = finalBuildPassed == TRUE ? 20 : 0
   * (null treated as not-passed) testBonus = testsTotal>0 ? round(20 * testsPassed/testsTotal) : 0
   * (no test credit when no tests) verdictBonus = CLEAN=20, FIXED_ISSUES=15, UNRESOLVED_ISSUES=5,
   * REVIEW_FAILED=0, null=10 penalty = unfixedCriticalCount * 5 score = clamp(0, 100, base +
   * buildBonus + testBonus + verdictBonus - penalty)
   *
   * <p>R-132 fix: previously `testBonus = 10` when `testsTotal == 0`, awarding a "neutral" 10
   * points to runs that had no test coverage at all. The strict UX review (2026-04-27) caught run
   * e48399bf surfacing `verifiedScore=65` with `accuracyScore=5` and zero tests — reputational
   * risk. The score now earns zero on the test axis when no tests exist; the v3 UI also gates the
   * headline display on `testsTotal>0` so a "Verified N" badge can't vouch for un-tested code.
   */
  int computeVerifiedScore(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount) {
    return computeVerifiedScore(
        finalBuildPassed,
        testsPassed,
        testsTotal,
        reviewerVerdictName,
        unfixedCriticalCount,
        null,
        null);
  }

  /**
   * P1.2 (GA-readiness, 2026-05-06) — overload with explicit endpoint-coverage signal so the
   * headline verifiedScore can credit a run for delivering every spec endpoint, independent of the
   * reviewer's text verdict. {@code endpointCoveragePercent} is taken straight from {@code
   * accuracyReport.endpointCoverage.coveragePercent} when present (it's a deterministic count of
   * implemented vs required endpoints, NOT an LLM-graded number).
   *
   * <p>Bonus: +10 when {@code endpointCoveragePercent >= 95}. Closes the audit-flagged scenario on
   * run {@code 0444c95f} where {@code endpointCoverage.coveragePercent=100} but the headline
   * verifiedScore only saw {@code finalBuildPassed} + {@code FIXED_ISSUES} and missed the
   * deterministic delivery signal entirely.
   *
   * <p>Final clamp at 100 means a perfect run (build pass + all tests + CLEAN + 100% endpoints)
   * yields exactly 100. The 5×{@code unfixedCritical} penalty stays — but {@link
   * #countUnfixedCritical} now excludes accuracy-synthesised issues (id prefix {@code SYNTH-ACC-}),
   * so the LLM read-budget bug can no longer drag this number down.
   */
  int computeVerifiedScore(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      Integer endpointCoveragePercent) {
    return computeVerifiedScore(
        finalBuildPassed,
        testsPassed,
        testsTotal,
        reviewerVerdictName,
        unfixedCriticalCount,
        endpointCoveragePercent,
        null);
  }

  /**
   * PR-R (2026-05-14) — spec-aware overload. Adds {@code testCoverageLevel} from {@link
   * com.navneet.executespec.reviewer.model.AccuracyReport#getTestCoverageLevel()} so the formula
   * can distinguish "no tests written because the spec didn't need any" (e.g. a hello.py script —
   * testCoverageLevel=GOOD/COMPREHENSIVE) from "no tests written but the spec implied they were
   * needed" (testCoverageLevel=NONE/PARTIAL).
   *
   * <p>Pre-PR-R, {@code testsTotal=0} unconditionally set the test factor to 0.5 (neutral), which
   * capped the headline score at 50 even on a clean spec that genuinely required no tests. Now the
   * AccuracyAssessmentService's already-emitted {@code testCoverageLevel} signal anchors the
   * factor.
   */
  int computeVerifiedScore(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      Integer endpointCoveragePercent,
      String testCoverageLevel) {
    // 6-arg / 7-arg call sites pre-date the 2026-05-19 additive rebuild; they
    // don't know filesGenerated. Pass -1 to mean "unknown" and let the new
    // formula fall back to its non-CODE_DELIVERED branch.
    return computeVerifiedScore(
        finalBuildPassed,
        testsPassed,
        testsTotal,
        reviewerVerdictName,
        unfixedCriticalCount,
        endpointCoveragePercent,
        testCoverageLevel,
        -1);
  }

  /**
   * 2026-05-19 — primary entry. Dispatches to additive (default) or legacy multiplicative based
   * on the {@code scoring.additive-v2} feature flag. Both formulae get all the inputs they need;
   * the multiplicative path simply ignores {@code filesGenerated}.
   */
  int computeVerifiedScore(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      Integer endpointCoveragePercent,
      String testCoverageLevel,
      int filesGenerated) {
    boolean additive =
        featureFlagService == null || featureFlagService.isEnabled(FLAG_ADDITIVE_SCORE);
    if (!additive) {
      return computeVerifiedScoreMultiplicative(
          finalBuildPassed,
          testsPassed,
          testsTotal,
          reviewerVerdictName,
          unfixedCriticalCount,
          endpointCoveragePercent,
          testCoverageLevel);
    }
    return computeVerifiedScoreAdditive(
        finalBuildPassed,
        testsPassed,
        testsTotal,
        reviewerVerdictName,
        unfixedCriticalCount,
        testCoverageLevel,
        filesGenerated);
  }

  /**
   * 2026-05-19 — additive rebuild. Replaces the multiplicative cliff with a weighted average
   * over the signals that actually fired.
   *
   * <p>Weights:
   *
   * <ul>
   *   <li><b>CODE_DELIVERED (30)</b> — primary factor. {@code filesGenerated > 0} ⇒ 100,
   *       otherwise 0. When this is 0 the headline floors to 0 regardless of other signals —
   *       the platform's job is to ship code; nothing else matters if it didn't.
   *   <li><b>BUILD (25)</b> — PASSING ⇒ 100, FAILING ⇒ 0. Skipped from the weighted average when
   *       {@code finalBuildPassed} is null (single-shot or patch-mode runs that don't run
   *       compile).
   *   <li><b>TESTS (20)</b> — when real tests ran: linear {@code passed/total × 100}. When no
   *       tests ran but {@link AccuracyReport#getTestCoverageLevel()} graded coverage: damped
   *       scale capped at 70 (GOOD/COMPREHENSIVE=70, PARTIAL=40, NONE=10) — LLM grading is fuzzy
   *       and shouldn't earn full credit. Null/missing ⇒ skipped.
   *   <li><b>REVIEWER (20)</b> — CLEAN=100, FIXED_ISSUES=85, UNRESOLVED_ISSUES=40,
   *       REVIEW_FAILED=10. Null ⇒ skipped.
   *   <li><b>CRITICALS (5)</b> — 100 − 20 × unfixedCriticalCount, floored at 0. Always counts
   *       (zero criticals earns the full 5 pts).
   * </ul>
   *
   * <p>Why additive over multiplicative: the multiplicative formula treats every factor as a
   * gate — one low factor crushes the headline. That produced "Failed 50%" banners on runs
   * where the build passed and the reviewer ran clean, just because the AccuracyAssessment LLM
   * happened to grade test coverage as PARTIAL. The customer-facing read of those runs was a
   * lie. Additive lets the user see "build passed + reviewer clean + tests light" and decide.
   *
   * <p>Why CODE_DELIVERED at 30: it's the only signal that maps directly to "the platform did
   * its job." Build/tests/reviewer are quality measures on top. When code wasn't generated the
   * other signals are theatre — hence the hard floor.
   *
   * <p>Why endpointFactor is gone: the reviewer's wiring pass already catches the same class of
   * bug the endpoint count was meant to catch. Double-counting forced two trust gates into the
   * old formula. Drop the signal, drop the gates.
   */
  int computeVerifiedScoreAdditive(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      String testCoverageLevel,
      int filesGenerated) {

    // CODE_DELIVERED hard floor. -1 = unknown (legacy 7-arg call) → treat as
    // "delivered" so the call site doesn't accidentally tank scores it can't see.
    boolean codeKnown = filesGenerated >= 0;
    boolean codeDelivered = !codeKnown || filesGenerated > 0;
    if (codeKnown && filesGenerated == 0) {
      return 0;
    }

    double weightedSum = 0.0;
    double weightTotal = 0.0;

    // CODE_DELIVERED — 30 pts, always counted when known (we just guarded the 0 case).
    if (codeKnown) {
      weightedSum += 30.0 * (codeDelivered ? 100.0 : 0.0);
      weightTotal += 30.0;
    }

    // BUILD — 25 pts, skipped when not measured.
    if (finalBuildPassed != null) {
      weightedSum += 25.0 * (Boolean.TRUE.equals(finalBuildPassed) ? 100.0 : 0.0);
      weightTotal += 25.0;
    }

    // TESTS — 20 pts. Real run data > LLM grade. Skip entirely if neither present.
    if (testsTotal > 0) {
      double ratio = Math.max(0.0, Math.min(1.0, (double) testsPassed / Math.max(1, testsTotal)));
      weightedSum += 20.0 * 100.0 * ratio;
      weightTotal += 20.0;
    } else if (testCoverageLevel != null) {
      Double dampedScore =
          switch (testCoverageLevel) {
            case "GOOD", "COMPREHENSIVE" -> 70.0; // capped — LLM grade, not measured
            case "PARTIAL" -> 40.0;
            case "NONE" -> 10.0;
            default -> null;
          };
      if (dampedScore != null) {
        weightedSum += 20.0 * dampedScore;
        weightTotal += 20.0;
      }
    }

    // REVIEWER — 20 pts.
    if (reviewerVerdictName != null) {
      double reviewerScore =
          switch (reviewerVerdictName) {
            case "CLEAN" -> 100.0;
            case "FIXED_ISSUES" -> 85.0;
            case "UNRESOLVED_ISSUES" -> 40.0;
            case "REVIEW_FAILED" -> 10.0;
            default -> 50.0;
          };
      weightedSum += 20.0 * reviewerScore;
      weightTotal += 20.0;
    }

    // CRITICALS — 5 pts. Always present.
    double criticalsScore = Math.max(0.0, 100.0 - 20.0 * Math.max(0, unfixedCriticalCount));
    weightedSum += 5.0 * criticalsScore;
    weightTotal += 5.0;

    if (weightTotal <= 0.0) return 0;
    double raw = weightedSum / weightTotal;
    return (int) Math.max(0, Math.min(100, Math.round(raw)));
  }

  /**
   * 2026-05-08 — multiplicative re-shape. Previous additive formula treated 4 conjunctive signals
   * (build / tests / reviewer / endpoints) as if they were independent contributors: a build
   * failure could only knock 20 points off a "perfect 100", which produced misleading scores like
   * 55/100 on runs that didn't compile. Multiplicative shape collapses the headline number cleanly
   * when any critical dimension is missing — a failed build → near-zero, a clean comprehensive run
   * → ~100. Bug-class double-counting between synth-issues and the dimension bonuses they restate
   * goes away naturally because we no longer subtract penalties on top of dimension factors.
   *
   * <p>Each factor is in [0, 1.0], with documented interpretations:
   *
   * <ul>
   *   <li><b>buildFactor</b> — 1.0 if {@code finalBuildPassed=true}, else 0.0. Build is binary at
   *       the project level; partial credit isn't meaningful.
   *   <li><b>testFactor</b> — testsPassed/testsTotal when tests ran; 0.5 ("unknown, neutral") when
   *       no tests were captured. We don't punish runs that have no test infra (covered by the v3
   *       UI's "testsTotal>0" headline gate, see R-132), but we also don't reward them.
   *       <b>2026-05-16 trust gate:</b> when reviewer is CLEAN or FIXED_ISSUES, build passed, and
   *       endpoint coverage is ≥95%, testFactor is floored at reviewerFactor to prevent the
   *       AccuracyAssessment LLM's "PARTIAL coverage" judgment from overriding the reviewer's
   *       wiring-pass + endpoint-coverage signals on specs that explicitly request minimal tests
   *       ("one JUnit test"). See OpenAI todo run 29b761fc analysis for the false-positive class
   *       this closes.
   *   <li><b>reviewerFactor</b> — CLEAN=1.0, FIXED_ISSUES=0.85, UNRESOLVED_ISSUES=0.4,
   *       REVIEW_FAILED=0.2, null=0.5. Captures the qualitative reviewer signal as a multiplicative
   *       damper.
   *   <li><b>endpointFactor</b> — endpointCoveragePercent / 100, with null treated as 1.0 (no spec
   *       endpoints means the coverage signal isn't applicable). Linear scaling replaces the
   *       previous binary-at-95% cliff that gave 1pp difference 10pp of score. <b>2026-05-10 trust
   *       gate:</b> when reviewer is CLEAN or FIXED_ISSUES, endpointFactor is floored at
   *       reviewerFactor to prevent the assessment LLM's read-budget-limited count from overriding
   *       the reviewer's wiring-pass verdict on the same dimension. See dev run 7483896a analysis
   *       (PR #95) for the false-positive class this closes.
   *   <li><b>criticalDamper</b> — 1.0 when no unfixed criticals; drops 5% per critical (to a floor
   *       of 0.5). Replaces the additive 5×N penalty that double-counted with SYNTH-BUILD/SYNTH-REV
   *       (which themselves restate the build/reviewer factors).
   * </ul>
   *
   * <p>Result: {@code raw = 100 * buildFactor * testFactor * reviewerFactor * endpointFactor *
   * criticalDamper}, rounded + clamped to [0, 100].
   *
   * <p>Backward compat: the {@code Boolean computeVerifiedScore(...)} 6-arg signature stays — call
   * sites don't change. Persisted scores from the old formula remain in the database; only new runs
   * get the new shape. The shift is observable in the dashboard (Verified N drops sharply on broken
   * runs) but no migration is required.
   */
  int computeVerifiedScoreMultiplicative(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      Integer endpointCoveragePercent) {
    return computeVerifiedScoreMultiplicative(
        finalBuildPassed,
        testsPassed,
        testsTotal,
        reviewerVerdictName,
        unfixedCriticalCount,
        endpointCoveragePercent,
        null);
  }

  /**
   * PR-R (2026-05-14) — spec-aware version of the multiplicative formula. Adds {@code
   * testCoverageLevel} as the test-factor anchor when no concrete tests were captured.
   *
   * <p>Bug it fixes: pre-PR-R, every run with {@code testsTotal=0} got {@code testFactor=0.5},
   * which mathematically capped the verifiedScore at 50 — even on specs that genuinely required no
   * tests (CLI scripts, single-file utilities, library refactors). User-reported case: a "hello.py"
   * smoke run got verifiedScore=50 with reviewer=CLEAN + build=passed + testCoverageLevel="GOOD".
   * The "GOOD" already meant "coverage matches what the spec implied"; we just weren't reading it.
   *
   * <p>Factor table when {@code testsTotal == 0}:
   *
   * <ul>
   *   <li><b>GOOD / COMPREHENSIVE</b> — coverage matches what the spec implied → 1.0 (full credit)
   *   <li><b>PARTIAL</b> — some coverage expected, less than what spec implied → 0.7
   *   <li><b>NONE</b> — spec implied tests were needed, none captured → 0.3 (real damper)
   *   <li><b>null</b> — AccuracyAssessment didn't run / pre-PR-R run → 0.5 (legacy neutral)
   * </ul>
   *
   * <p>When {@code testsTotal > 0}, behaviour is unchanged: linear pass/total ratio.
   */
  int computeVerifiedScoreMultiplicative(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      Integer endpointCoveragePercent,
      String testCoverageLevel) {

    double buildFactor = Boolean.TRUE.equals(finalBuildPassed) ? 1.0 : 0.0;

    double testFactor;
    if (testsTotal <= 0) {
      // PR-R (2026-05-14) — spec-aware. Trust AccuracyAssessment's testCoverageLevel signal:
      // GOOD/COMPREHENSIVE means "coverage matches what the spec needed" (which for a no-test
      // spec is zero); NONE means the spec implied tests but none were written.
      testFactor = testFactorFromCoverageLevel(testCoverageLevel);
    } else {
      testFactor = Math.max(0.0, Math.min(1.0, (double) testsPassed / Math.max(1, testsTotal)));
    }

    double reviewerFactor;
    if (reviewerVerdictName == null) {
      reviewerFactor = 0.5; // unknown — half-credit not zero, but a clear damper
    } else {
      reviewerFactor =
          switch (reviewerVerdictName) {
            case "CLEAN" -> 1.0;
            case "FIXED_ISSUES" -> 0.85;
            case "UNRESOLVED_ISSUES" -> 0.4;
            case "REVIEW_FAILED" -> 0.2;
            default -> 0.5;
          };
    }

    double endpointFactor;
    if (endpointCoveragePercent == null) {
      endpointFactor = 1.0; // signal not applicable (e.g. CLI / library spec, no HTTP)
    } else {
      endpointFactor = Math.max(0.0, Math.min(1.0, endpointCoveragePercent / 100.0));
    }

    // 2026-05-10 — endpoint-coverage trust gate.
    //
    // Root cause from dev run 7483896a (ppl-ai, PR #95 analysis): the
    // `endpointCoveragePercent` field on AccuracyReport is documented as
    // "deterministic" (a count of expected vs implemented endpoints from
    // the spec) but the count is RELATIVE TO THE LLM'S READ BUDGET. When
    // the assessment LLM only reads 6 of N source files, it
    // under-counts implemented endpoints and reports e.g. 40% coverage
    // even when the reviewer's wiring pass found every endpoint
    // correctly registered. Result: the multiplicative formula compounds
    // the false-low coverage signal — verifiedScore on 7483896a came out
    // 17/100 with `100 × 1.0 × 0.5 × 0.85 × 0.4 × 1.0 = 17` even though
    // every concrete pipeline signal was healthy (build PASSED, reviewer
    // CLEAN/FIXED_ISSUES, no real unfixed criticals).
    //
    // The reviewer's wiring pass IS the deterministic check on endpoint
    // correctness — it walks the route tables of every supported
    // framework (Spring component-scan, Express routers, FastAPI route
    // decorators, etc.). When the reviewer's verdict is CLEAN or
    // FIXED_ISSUES, trust THAT signal over the LLM's read-budget-limited
    // endpoint count: floor endpointFactor at reviewerFactor so a strong
    // reviewer signal can't be overridden by an LLM hallucination on the
    // same dimension. UNRESOLVED_ISSUES / REVIEW_FAILED stays
    // un-floored — those verdicts mean the wiring pass wasn't decisive,
    // so the LLM signal is the only check we have.
    if ("CLEAN".equals(reviewerVerdictName) || "FIXED_ISSUES".equals(reviewerVerdictName)) {
      endpointFactor = Math.max(endpointFactor, reviewerFactor);
    }

    // 2026-05-16 — test-coverage trust gate (mirror of the endpoint gate).
    //
    // Root cause from the OpenAI todo-app run (29b761fc, vScore 60 vs 85
    // for Anthropic/Gemini on the same spec): the spec said "Include
    // ONE JUnit test for the controller". OpenAI shipped exactly one
    // @Test method covering all 4 endpoints in a single happy-path flow
    // — literally what the spec asked for. Anthropic / Gemini
    // over-delivered with 9 / 5 @Test methods respectively. The
    // AccuracyAssessment LLM then judged OpenAI's testCoverageLevel as
    // PARTIAL (factor 0.7) and Anthropic / Gemini as GOOD (factor 1.0).
    // Multiplicatively that's a 30% score gap caused entirely by the
    // model following the spec literally.
    //
    // Same fix as endpointFactor: when the reviewer's wiring pass came
    // back CLEAN or FIXED_ISSUES AND the build passed AND endpoint
    // coverage is ≥95%, the build is provably wired up — at that point
    // an LLM "PARTIAL" testCoverageLevel signal is more likely measuring
    // breadth-of-tests-relative-to-some-ideal than spec compliance.
    // Floor testFactor at reviewerFactor so a strong end-to-end signal
    // doesn't get clobbered by the LLM judge's preference for
    // exhaustive over minimal.
    //
    // Only fires when `testsTotal == 0` (i.e. we couldn't actually run
    // the tests). When real tests ran the pass/total ratio stays the
    // truth; the LLM judge is unused.
    if (testsTotal <= 0
        && ("CLEAN".equals(reviewerVerdictName) || "FIXED_ISSUES".equals(reviewerVerdictName))
        && endpointCoveragePercent != null
        && endpointCoveragePercent >= 95) {
      testFactor = Math.max(testFactor, reviewerFactor);
    }

    // Each unfixed critical knocks 5% off, floored at 0.5. Substantially weaker than the
    // old 5×N additive penalty because the dimension factors above already reflect the
    // build / reviewer signals; the damper is for issues the dimensions don't already
    // catch (e.g. real LLM-flagged bugs that didn't tip the verdict).
    double criticalDamper = Math.max(0.5, 1.0 - 0.05 * Math.max(0, unfixedCriticalCount));

    double raw =
        100.0 * buildFactor * testFactor * reviewerFactor * endpointFactor * criticalDamper;
    return (int) Math.max(0, Math.min(100, Math.round(raw)));
  }

  /**
   * PR-R (2026-05-14) — maps the AccuracyAssessment {@code testCoverageLevel} signal into a 0.0-1.0
   * multiplicative test factor when no concrete tests were captured. Single chokepoint so the
   * multiplicative + rubric scorers can't drift from each other.
   *
   * <p>The signal is the LLM-judged "how does delivered test coverage compare to what the spec
   * implied was needed":
   *
   * <ul>
   *   <li>{@code COMPREHENSIVE} / {@code GOOD} — spec didn't need more than what was delivered →
   *       full credit (1.0)
   *   <li>{@code PARTIAL} — some coverage was implied + delivered, but not all of it → 0.7
   *   <li>{@code NONE} — spec implied tests were needed + none were written → 0.3 (real damper)
   *   <li>{@code null} or unknown — AccuracyAssessment skipped / pre-PR-R run → 0.5 (legacy)
   * </ul>
   */
  static double testFactorFromCoverageLevel(String testCoverageLevel) {
    if (testCoverageLevel == null) return 0.5;
    return switch (testCoverageLevel.toUpperCase()) {
      case "COMPREHENSIVE", "GOOD" -> 1.0;
      case "PARTIAL" -> 0.7;
      case "NONE" -> 0.3;
      default -> 0.5; // forward-compat with new values the AccuracyReport prompt might invent
    };
  }

  /**
   * PR-R (2026-05-14) — rubric-side equivalent of {@link #testFactorFromCoverageLevel}, scaled to
   * the rubric's 20-point test slot. Same coverage-level → score mapping shape.
   */
  static int rubricTestPointsFromCoverageLevel(String testCoverageLevel) {
    return (int) Math.round(20.0 * testFactorFromCoverageLevel(testCoverageLevel));
  }

  /**
   * PR-E Phase A (2026-05-13) — additive rubric. Each dimension contributes points independently so
   * a single zero factor doesn't collapse the headline number the way the multiplicative {@link
   * #computeVerifiedScoreMultiplicative} does. Sum is clamped to [0, 100].
   *
   * <ul>
   *   <li><b>Build (25 pts):</b> finalBuildPassed=true → 25; else 0
   *   <li><b>Tests (20 pts):</b> linear testsPassed/testsTotal × 20; testsTotal=0 → {@link
   *       #rubricTestPointsFromCoverageLevel} (PR-R, spec-aware)
   *   <li><b>Reviewer (20 pts):</b> CLEAN=20 / FIXED_ISSUES=15 / UNRESOLVED_ISSUES=5 /
   *       REVIEW_FAILED=0 / null=10 (neutral)
   *   <li><b>Endpoints (15 pts):</b> linear endpointCoveragePercent × 0.15; null=15 (n/a)
   *   <li><b>Critical-damper (10 pts):</b> max(0, 10 − unfixedCriticalCount)
   *   <li><b>Code quality (10 pts):</b> reviewerVerdict ≠ REVIEW_FAILED → 10; else 0
   * </ul>
   *
   * <p>Same signals as the multiplicative formula but a different aggregation shape — both persist
   * alongside each other (verifiedScore + rubricScore) so the UI can pick the more informative one
   * per run.
   */
  int computeRubricScore(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      Integer endpointCoveragePercent) {
    return computeRubricScore(
        finalBuildPassed,
        testsPassed,
        testsTotal,
        reviewerVerdictName,
        unfixedCriticalCount,
        endpointCoveragePercent,
        null);
  }

  /**
   * PR-R (2026-05-14) — spec-aware version. Mirrors the multiplicative formula's {@code
   * testCoverageLevel} branch: when {@code testsTotal == 0} we anchor the test slot on the
   * AccuracyAssessment coverage signal instead of the pre-PR-R hardcoded "10 pts neutral".
   */
  int computeRubricScore(
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      Integer endpointCoveragePercent,
      String testCoverageLevel) {
    int total = 0;

    // Build — binary 25 / 0
    if (Boolean.TRUE.equals(finalBuildPassed)) total += 25;

    // Tests — linear ratio scaled to 20 pts; spec-aware fallback when no tests captured (PR-R).
    if (testsTotal <= 0) {
      total += rubricTestPointsFromCoverageLevel(testCoverageLevel);
    } else {
      double ratio = (double) testsPassed / Math.max(1, testsTotal);
      total += (int) Math.round(20.0 * Math.max(0.0, Math.min(1.0, ratio)));
    }

    // Reviewer verdict — 20 / 15 / 5 / 0 / 10 (null = neutral)
    if (reviewerVerdictName == null) {
      total += 10;
    } else {
      total +=
          switch (reviewerVerdictName) {
            case "CLEAN" -> 20;
            case "FIXED_ISSUES" -> 15;
            case "UNRESOLVED_ISSUES" -> 5;
            case "REVIEW_FAILED" -> 0;
            default -> 10;
          };
    }

    // Endpoint coverage — linear 15 pts; n/a → 15 (no spec endpoints means signal not relevant)
    if (endpointCoveragePercent == null) {
      total += 15;
    } else {
      double ratio = Math.max(0.0, Math.min(1.0, endpointCoveragePercent / 100.0));
      total += (int) Math.round(15.0 * ratio);
    }

    // Critical-issue damper — 10 minus 1 per critical, floored at 0
    total += Math.max(0, 10 - Math.max(0, unfixedCriticalCount));

    // Code quality — 10 pts if the reviewer ran at all (anything except REVIEW_FAILED)
    if (!"REVIEW_FAILED".equals(reviewerVerdictName)) total += 10;

    return Math.max(0, Math.min(100, total));
  }

  /**
   * Maps the V1-compat {@link ReviewVerdict} stored on {@link ReviewReport} back to the V2 {@code
   * ReviewerResult.Overall} enum name. Persisted as-is in validation_report.reviewer_verdict so the
   * UI and downstream jobs can discriminate CLEAN vs FIXED_ISSUES (both collapse to APPROVED in
   * V1).
   */
  static String mapVerdictToV2Name(ReviewVerdict v) {
    if (v == null) return null;
    return switch (v) {
      case APPROVED -> "CLEAN";
      case APPROVED_WITH_NOTES -> "FIXED_ISSUES";
      case NEEDS_FIXES -> "UNRESOLVED_ISSUES";
      case REJECTED -> "REVIEW_FAILED";
    };
  }

  /**
   * Count unfixed CRITICAL issues for the verifiedScore penalty.
   *
   * <p>P1.2 (GA-readiness, 2026-05-06): synthesised accuracy issues (id prefix {@code SYNTH-ACC-})
   * are derived from the LLM's KEY SOURCE FILES read budget — when the LLM can't see file contents,
   * it emits "missing requirement" or "missing endpoint" issues that don't reflect the actual
   * implementation. Filter them out of the verifiedScore penalty so the read-budget bug can't drag
   * the headline number down. They're still kept in {@code review.getIssues()} for the dashboard
   * issues panel and {@link com.navneet.executespec.scoring.ReviewScoringService}'s 6-dimension
   * breakdown — only the headline {@code verifiedScore} is shielded.
   *
   * <p>Real reviewer-flagged issues (build failures, reviewer UNRESOLVED, etc.) start with {@code
   * SYNTH-BUILD-} or {@code SYNTH-REV-} OR have an LLM-assigned id with no SYNTH prefix; both keep
   * counting.
   */
  private int countUnfixedCritical(ReportInput input) {
    if (input.review() == null || input.review().getIssues() == null) return 0;
    return (int)
        input.review().getIssues().stream()
            .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
            .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
            // SYNTH-ACC-* prefix filter (P1.2 / GA-readiness, 2026-05-06).
            .filter(i -> i.getId() == null || !i.getId().startsWith("SYNTH-ACC-"))
            // 2026-05-10 (defense-in-depth) — also drop CRITICALs whose id
            // is missing AND whose description matches the exact
            // AccuracyAssessmentService output prefixes (see
            // RunOrchestrator.synthesiseReviewIssues line ~3636). Belt-
            // and-braces against any future code path that emits the
            // accuracy-derived issue without remembering the SYNTH-ACC-*
            // id stamp. Real reviewer-flagged criticals never use these
            // exact prefixes.
            .filter(
                i -> i.getId() != null || !isAccuracyHallucinationDescription(i.getDescription()))
            .count();
  }

  /**
   * Predicate that matches the deterministic prefixes RunOrchestrator.synthesiseReviewIssues emits
   * when translating AccuracyAssessmentService output into ReviewIssue records. Used by
   * countUnfixedCritical as a defensive filter for any future call site that bypasses the
   * SYNTH-ACC-* id stamp. Kept as a separate predicate so a test can pin the exact prefix list.
   */
  static boolean isAccuracyHallucinationDescription(String description) {
    if (description == null) return false;
    String d = description.stripLeading();
    return d.startsWith("Spec requirement not implemented:")
        || d.startsWith("Spec requirement only partially implemented:")
        || d.startsWith("Endpoint from spec not implemented:")
        || d.startsWith("Endpoint exists but");
  }

  private String truncate(String s, int maxLen) {
    if (s == null) return "";
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
  }
}
