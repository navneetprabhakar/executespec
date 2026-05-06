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
import com.navneet.executespec.reviewer.model.ReviewReportV2;
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

      // 3. Extract accuracy data from ReviewReportV2 (Phase 3 output)
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
      int verifiedScore =
          computeVerifiedScore(
              finalBuildPassed,
              testsPassedCount,
              testsTotalCount,
              reviewerVerdict,
              unfixedCritical,
              endpointCoveragePercent);

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
    ReviewReportV2 review = input.review();
    if (review != null && review.getIssues() != null) {
      for (ReviewIssue ri : review.getIssues()) {
        String fixNote = null;
        if (ri.isAutoFixed()) {
          fixNote =
              ri.isFixSucceeded()
                  ? "Auto-fixed successfully"
                  : "Auto-fix failed after retry with model escalation — unresolved";
        }
        issues.add(
            new ReportIssueDTO(
                ri.getDescription(),
                mapSeverity(ri.getSeverity()),
                fixNote,
                "REVIEWER_AGENT",
                ri.getFile()));
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
    ReviewReportV2 review = input.review();
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
          case "LEGACY_UNKNOWN" -> heuristic;
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
        finalBuildPassed, testsPassed, testsTotal, reviewerVerdictName, unfixedCriticalCount, null);
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
    int base = 40;
    int buildBonus = Boolean.TRUE.equals(finalBuildPassed) ? 20 : 0;
    int testBonus = (testsTotal > 0) ? (int) Math.round(20.0 * testsPassed / testsTotal) : 0;
    int verdictBonus;
    if (reviewerVerdictName == null) {
      verdictBonus = 10;
    } else {
      verdictBonus =
          switch (reviewerVerdictName) {
            case "CLEAN" -> 20;
            case "FIXED_ISSUES" -> 15;
            case "UNRESOLVED_ISSUES" -> 5;
            case "REVIEW_FAILED" -> 0;
            default -> 10;
          };
    }
    int endpointBonus = (endpointCoveragePercent != null && endpointCoveragePercent >= 95) ? 10 : 0;
    int penalty = Math.max(0, unfixedCriticalCount) * 5;
    int raw = base + buildBonus + testBonus + verdictBonus + endpointBonus - penalty;
    return Math.max(0, Math.min(100, raw));
  }

  /**
   * Maps the V1-compat {@link ReviewVerdict} stored on {@link ReviewReportV2} back to the V2 {@code
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
            .filter(i -> i.getId() == null || !i.getId().startsWith("SYNTH-ACC-"))
            .count();
  }

  private String truncate(String s, int maxLen) {
    if (s == null) return "";
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
  }
}
