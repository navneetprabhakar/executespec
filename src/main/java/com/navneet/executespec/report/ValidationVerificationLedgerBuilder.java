package com.navneet.executespec.report;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.ReportIssueDTO;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds the reloadable verification ledger from persisted report evidence.
 *
 * <p>This is deliberately a pure, package-local collaborator: it does not load state, call an LLM,
 * or mutate the report. Keeping this boundary separate from report persistence makes the evidence
 * contract reusable by authenticated and shared report projections without changing the score or
 * report-generation paths.
 */
@Slf4j
final class ValidationVerificationLedgerBuilder {

  /**
   * RR-1646 / RR-1660 — provenance label for the persisted concrete-test counts.
   *
   * <p>{@code DETERMINISTIC} is the one label this ledger uses to claim independence from an LLM.
   * RR-1646 downgraded these counts to {@code UNVERIFIED_COUNT}/MEDIUM because report files read
   * off disk carried no proof they described THIS run: no exit-code check, no per-run directory
   * reset (RR-1582). That was honest but pessimistic — it also understated genuinely proven runs.
   *
   * <p>RR-1660 restores the strong label where, and only where, the producer positively proved
   * provenance: V317 persists {@code validation_report.test_execution_verified}, set TRUE only when
   * the report directory was reset before launch, the runner's exit status was checked, and every
   * parsed report postdates the launch.
   *
   * <p><b>Three-valued, and null is unproven.</b> {@code null} (no signal recorded — every pre-V317
   * row, and any producer that establishes no freshness) and {@code FALSE} both keep {@code
   * UNVERIFIED_COUNT}/MEDIUM. Only TRUE promotes. Treating null as proven-false would be equally
   * wrong; both non-TRUE states mean the same thing here — not proven.
   */
  private static final String UNPROVEN_TEST_COUNT_SOURCE = "UNVERIFIED_COUNT";

  private static final String FUNCTIONAL_TEST_SOURCE = "DETERMINISTIC_FUNCTIONAL";

  private static final String WORKSPACE_TEST_SOURCE = "DETERMINISTIC_WORKSPACE_SUITE";

  private static final String LEGACY_PROVEN_TEST_SOURCE = "DETERMINISTIC_EXECUTION";

  private static final String UNPROVEN_TEST_COUNT_CONFIDENCE = "MEDIUM";

  private static final String PROVEN_TEST_COUNT_CONFIDENCE = "HIGH";

  /** True only when V317 recorded a positive execution-provenance proof for these counts. */
  private static boolean testCountsProven(ValidationReport entity) {
    return Boolean.TRUE.equals(entity.getTestExecutionVerified());
  }

  private static String testCountSource(ValidationReport entity) {
    if (!testCountsProven(entity)) return UNPROVEN_TEST_COUNT_SOURCE;
    return switch (testEvidenceScope(entity)) {
      case "LIVE_PREVIEW_FUNCTIONAL" -> FUNCTIONAL_TEST_SOURCE;
      case "WORKSPACE_TEST_SUITE" -> WORKSPACE_TEST_SOURCE;
      default -> LEGACY_PROVEN_TEST_SOURCE;
    };
  }

  private static String testCountConfidence(ValidationReport entity) {
    return testCountsProven(entity) ? PROVEN_TEST_COUNT_CONFIDENCE : UNPROVEN_TEST_COUNT_CONFIDENCE;
  }

  private static String testEvidenceScope(ValidationReport entity) {
    return entity.getTestEvidenceScope() == null ? "" : entity.getTestEvidenceScope();
  }

  private static boolean functionalTestCountsProven(ValidationReport entity) {
    return testCountsProven(entity) && "LIVE_PREVIEW_FUNCTIONAL".equals(testEvidenceScope(entity));
  }

  private final ObjectMapper objectMapper;
  private final ValidationReportScoreZeroEvidenceBuilder scoreZeroEvidenceBuilder;

  ValidationVerificationLedgerBuilder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.scoreZeroEvidenceBuilder = new ValidationReportScoreZeroEvidenceBuilder(objectMapper);
  }

  List<VerificationEvidenceDTO> build(ValidationReport entity, List<ReportIssueDTO> issues) {
    List<VerificationEvidenceDTO> out = new ArrayList<>();

    out.add(
        evidence(
            "code-delivered",
            "Code delivered",
            "CODE",
            entity.getFilesGenerated() < 0
                ? "UNKNOWN"
                : entity.getFilesGenerated() > 0 ? "PASS" : "FAIL",
            "DETERMINISTIC",
            entity.getFilesGenerated() < 0 ? "MEDIUM" : "HIGH",
            "The run produced source changes.",
            entity.getFilesGenerated() < 0
                ? "The historical report did not retain a measured product-file count."
                : entity.getFilesGenerated() > 0
                    ? entity.getFilesGenerated() + " file(s) generated or modified."
                    : "No generated files were captured; the score cannot vouch for delivery.",
            "validation_report.files_generated",
            entity.getFilesGenerated(),
            null));

    Boolean build = entity.getFinalBuildPassed();
    out.add(
        evidence(
            "build",
            "Build",
            "BUILD",
            build == null ? "UNKNOWN" : build ? "PASS" : "FAIL",
            "DETERMINISTIC",
            build == null ? "MEDIUM" : "HIGH",
            "The generated workspace compiled or passed the configured build check.",
            build == null
                ? "No final build signal was stored for this report."
                : build ? "Final build passed." : "Final build failed.",
            commandArtifact(
                entity.getBuildCommand(),
                entity.getBuildWorkingDirectory(),
                entity.getBuildExitCode(),
                "validation_report.build_output"),
            build == null ? null : build ? 1 : 0,
            build == null ? null : 1));

    out.add(
        WorkflowVerificationEvidenceMapper.from(
            entity.getWorkflowConfigEvidenceJson(), objectMapper));

    if (entity.getBootVerdict() != null) {
      String verdict = entity.getBootVerdict();
      String status =
          "HEALTHY".equals(verdict) ? "PASS" : "UNHEALTHY".equals(verdict) ? "WARN" : "FAIL";
      out.add(
          evidence(
              "boot-verification",
              "Application boot verification",
              "BUILD",
              status,
              "DETERMINISTIC",
              "HIGH",
              "The generated application built, started, and was probed by the configured health"
                  + " check.",
              (entity.getBootReason() == null || entity.getBootReason().isBlank())
                  ? "Boot verdict: " + verdict + "."
                  : "Boot verdict: " + verdict + "; " + entity.getBootReason(),
              "validation_report.boot_verdict",
              Boolean.TRUE.equals(entity.getBootHealthy()) ? 1 : 0,
              1));
    }

    if (entity.getExecutableVerificationStatus() != null) {
      String executableStatus = entity.getExecutableVerificationStatus();
      // RR-1646 — this row is deterministic only while it rests on the build signal. Once the
      // status was decided by the concrete-test counts, it inherits their unproven provenance.
      boolean restsOnTestCounts = Boolean.TRUE.equals(entity.getExecutableTestsRun());
      boolean testBackedProofIsAdmissible = !restsOnTestCounts || testCountsProven(entity);
      out.add(
          evidence(
              "executable-verification",
              "Executable verification",
              "VERIFICATION",
              !testBackedProofIsAdmissible
                  ? "UNKNOWN"
                  : switch (executableStatus) {
                    case "PASSED" -> "PASS";
                    case "BUILD_ONLY" -> "WARN";
                    case "FAILED" -> "FAIL";
                    default -> "UNKNOWN";
                  },
              restsOnTestCounts ? testCountSource(entity) : "DETERMINISTIC",
              restsOnTestCounts ? testCountConfidence(entity) : "HIGH",
              restsOnTestCounts && !testCountsProven(entity)
                  ? "The admitted product diff has reloadable build evidence and a test-runner"
                      + " result whose execution freshness is not independently proven."
                  : "The admitted product diff has reloadable build and concrete-test evidence.",
              "Executable verification: "
                  + executableStatus
                  + (entity.getExecutionFailureStage() == null
                      ? "."
                      : "; failure stage: " + entity.getExecutionFailureStage() + "."),
              "validation_report.executable_verification_status",
              "PASSED".equals(executableStatus) ? 1 : 0,
              1));
    }

    Integer testsPassed = entity.getTestsPassed();
    Integer testsTotal = entity.getTestsTotal();
    Integer testsSkipped = entity.getTestsSkipped();
    int total = testsTotal == null ? 0 : testsTotal;
    int passed = testsPassed == null ? 0 : testsPassed;
    int skipped = testsSkipped == null ? 0 : testsSkipped;
    boolean countsProven = total > 0 && testCountsProven(entity);
    boolean suitePassed =
        countsProven && passed > 0 && skipped >= 0 && (long) passed + skipped == total;
    out.add(
        evidence(
            "tests",
            "Tests",
            "TESTS",
            !countsProven ? "UNKNOWN" : suitePassed ? "PASS" : passed > 0 ? "WARN" : "FAIL",
            countsProven ? testCountSource(entity) : UNPROVEN_TEST_COUNT_SOURCE,
            countsProven ? PROVEN_TEST_COUNT_CONFIDENCE : UNPROVEN_TEST_COUNT_CONFIDENCE,
            total <= 0
                ? "No independently admissible functional-test result is attached to this report."
                    + " Worker-authored workspace tests are diagnostic and cannot prove product"
                    + " quality by themselves."
                : functionalTestCountsProven(entity)
                    ? "Generated black-box functional tests ran against this run's live preview."
                        + " The counts are bound to this execution and exercise the product"
                        + " boundary."
                    : countsProven
                        ? "A fresh workspace test suite executed successfully. This is"
                            + " deterministic code-level evidence, but it does not prove deployed"
                            + " product journeys or external integrations end to end."
                        : "Persisted test counts exist, but the report carries no admissible"
                            + " independent functional-execution proof. Fresh unit-test output or"
                            + " stale runner output cannot certify end-to-end product quality.",
            total <= 0
                ? "Independent end-to-end/functional verification is pending; workspace tests are"
                    + " diagnostic only."
                : passed
                    + "/"
                    + total
                    + " tests passed"
                    + (skipped > 0 ? "; " + skipped + " skipped" : "")
                    + (functionalTestCountsProven(entity)
                        ? " (functional)."
                        : countsProven ? " (workspace suite only)." : " (counts unverified)."),
            commandArtifact(
                entity.getTestCommand(),
                entity.getTestWorkingDirectory(),
                entity.getTestExitCode(),
                "validation_report.test_output"),
            total <= 0 ? null : passed,
            total <= 0 ? null : total));

    String reviewer = entity.getReviewerVerdict();
    out.add(
        evidence(
            "reviewer",
            "Reviewer",
            "REVIEW",
            reviewerStatus(reviewer),
            "LLM_REVIEWER",
            "MEDIUM",
            "The reviewer pass found no unresolved critical implementation issues.",
            reviewer == null ? "Reviewer verdict unavailable." : "Reviewer verdict: " + reviewer,
            "validation_report.reviewer_verdict",
            null,
            null));

    int blockingIssues = countBlockingIssues(issues);
    out.add(
        evidence(
            "report-issues",
            "Report issues",
            "ISSUES",
            blockingIssues == 0 ? "PASS" : "FAIL",
            "HYBRID",
            "MEDIUM",
            "No non-lint build/review/bug issues remain open in the report.",
            blockingIssues == 0
                ? "No non-lint report issues."
                : blockingIssues + " non-lint issue(s) remain in the report.",
            "validation_report.issues_json",
            blockingIssues,
            null));

    ValidationAccuracyFacts accuracy =
        ValidationAccuracyFacts.parse(objectMapper, entity.getAccuracyJson());
    Integer effectiveVerifiedScore =
        entity.getVerifiedScore() == null
            ? null
            : accuracy.specCited()
                ? ValidationReportScorePolicy.capForAdmittedSpecGaps(
                    entity.getVerifiedScore(),
                    accuracy.score() == null ? 0 : accuracy.score(),
                    accuracy.incompleteRequirements(),
                    accuracy.unknownRequirements())
                : entity.getVerifiedScore();
    Integer effectiveRubricScore =
        entity.getRubricScore() == null
            ? null
            : accuracy.specCited()
                ? ValidationReportScorePolicy.capForAdmittedSpecGaps(
                    entity.getRubricScore(),
                    accuracy.score() == null ? 0 : accuracy.score(),
                    accuracy.incompleteRequirements(),
                    accuracy.unknownRequirements())
                : entity.getRubricScore();
    out.add(
        evidence(
            "spec-adherence",
            "Spec adherence",
            "SPEC",
            accuracy.score() == null || !accuracy.specCited()
                ? "UNKNOWN"
                : accuracy.unknownRequirements() > 0
                    ? "WARN"
                    : accuracy.score() >= 85 ? "PASS" : accuracy.score() >= 70 ? "WARN" : "FAIL",
            "LLM_ASSESSMENT",
            "LOW",
            "The delivered code appears to satisfy the natural-language spec.",
            accuracy.score() == null
                ? "Accuracy assessment was not captured."
                : "LLM assessment: "
                    + accuracy.score()
                    + "/100"
                    + (accuracy.grade() == null ? "" : " (" + accuracy.grade() + ")")
                    + (accuracy.unknownRequirements() > 0
                        ? "; " + accuracy.unknownRequirements() + " requirement(s) unverified"
                        : "")
                    + ".",
            accuracy.specArtifact("validation_report.accuracy_json"),
            accuracy.score(),
            accuracy.score() == null ? null : 100));

    out.add(
        ValidationEndpointCoverageEvidence.build(
            entity,
            accuracy.endpointCoverage(),
            accuracy.endpointCited(),
            accuracy.endpointArtifact("validation_report.accuracy_json.endpointCoverage")));

    if (accuracy.testCoverageLevel() != null) {
      String level = accuracy.testCoverageLevel();
      out.add(
          evidence(
              "test-coverage-judgment",
              "Test coverage judgment",
              "TESTS",
              !accuracy.testCited()
                  ? "UNKNOWN"
                  : switch (level.toUpperCase()) {
                    case "GOOD", "COMPREHENSIVE" -> "PASS";
                    case "PARTIAL" -> "WARN";
                    case "NONE" -> "FAIL";
                    default -> "UNKNOWN";
                  },
              "LLM_ASSESSMENT",
              "LOW",
              "Test coverage matches what the spec implied should be tested.",
              "LLM test coverage judgment: " + level + ".",
              accuracy.testArtifact("validation_report.accuracy_json.testCoverageLevel"),
              null,
              null));
    }

    VerificationEvidenceDTO zeroCause =
        scoreZeroEvidenceBuilder.build(entity, effectiveVerifiedScore);
    if (zeroCause != null) {
      out.add(zeroCause);
    }

    TrustCap trustCap =
        trustCap(
            100,
            entity.getFinalBuildPassed(),
            passed,
            total,
            skipped,
            entity.getReviewerVerdict(),
            countUnfixedCriticalIssues(issues),
            accuracy.testCited() ? accuracy.testCoverageLevel() : null);
    if (trustCap.cap() < 100) {
      out.add(
          evidence(
              "trust-cap",
              "Trust cap",
              "SCORE",
              entity.getVerifiedScore() != null && entity.getVerifiedScore() <= trustCap.cap()
                  ? "WARN"
                  : "UNKNOWN",
              "INTERNAL_FORMULA",
              "HIGH",
              "The headline score is capped when verification signals say the run needs review.",
              "Maximum credible score capped at "
                  + trustCap.cap()
                  + " because "
                  + trustCap.reason()
                  + ".",
              "validation_report.*",
              entity.getVerifiedScore(),
              trustCap.cap()));
    }

    out.add(
        evidence(
            "score-summary",
            "Internal score",
            "SCORE",
            effectiveVerifiedScore == null
                ? "UNKNOWN"
                : effectiveVerifiedScore >= 85
                    ? "PASS"
                    : effectiveVerifiedScore >= 70 ? "WARN" : "FAIL",
            "INTERNAL_FORMULA",
            "MEDIUM",
            "The internal routing score summarizes the available evidence.",
            "verifiedScore="
                + (effectiveVerifiedScore == null ? "n/a" : effectiveVerifiedScore)
                + (effectiveRubricScore == null ? "" : ", rubricScore=" + effectiveRubricScore)
                + ". Treat this as a summary, not independent proof.",
            "validation_report.verified_score",
            effectiveVerifiedScore,
            effectiveVerifiedScore == null ? null : 100));

    return out.stream()
        .map(
            evidence ->
                ValidationReportScoreZeroEvidenceBuilder.bindWorkspaceGeneration(
                    evidence, entity.getWorkspaceGeneration()))
        .toList();
  }

  private static String commandArtifact(
      String command, String workingDirectory, Integer exitCode, String outputArtifact) {
    if (command == null || command.isBlank()) {
      return outputArtifact;
    }
    return "command="
        + command
        + " · cwd="
        + (workingDirectory == null ? "unknown" : workingDirectory)
        + " · exit="
        + (exitCode == null ? "unknown" : exitCode)
        + " · output="
        + outputArtifact;
  }

  private VerificationEvidenceDTO evidence(
      String id,
      String label,
      String category,
      String status,
      String source,
      String confidence,
      String claim,
      String summary,
      String artifact,
      Integer observed,
      Integer total) {
    return new VerificationEvidenceDTO(
        id,
        label,
        category,
        status,
        source,
        confidence,
        null,
        claim,
        summary,
        artifact,
        observed,
        total);
  }

  private String reviewerStatus(String reviewerVerdict) {
    if (reviewerVerdict == null) return "UNKNOWN";
    return switch (reviewerVerdict) {
      case "CLEAN", "FIXED_ISSUES" -> "PASS";
      case "UNRESOLVED_ISSUES" -> "WARN";
      case "REVIEW_FAILED" -> "FAIL";
      default -> "UNKNOWN";
    };
  }

  private int countBlockingIssues(List<ReportIssueDTO> issues) {
    if (issues == null) return 0;
    int count = 0;
    for (ReportIssueDTO issue : issues) {
      if (issue == null || issue.severity() == null) continue;
      if ("Auto-fixed successfully".equals(issue.fix())) continue;
      if (!"WOULD_FAIL_LINT".equals(issue.severity())) count++;
    }
    return count;
  }

  int countUnfixedCriticalIssues(List<ReportIssueDTO> issues) {
    if (issues == null) return 0;
    int count = 0;
    for (ReportIssueDTO issue : issues) {
      if (issue == null || !"WOULD_CAUSE_BUG".equals(issue.severity())) continue;
      if ("ACCURACY_LLM".equals(issue.stage())) continue;
      if (issue.fix() != null && issue.fix().startsWith("Auto-fixed successfully")) continue;
      count++;
    }
    return count;
  }

  record TrustCap(int score, int cap, String reason) {}

  TrustCap trustCap(
      int rawScore,
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      String testCoverageLevel) {
    return trustCap(
        rawScore,
        finalBuildPassed,
        testsPassed,
        testsTotal,
        0,
        reviewerVerdictName,
        unfixedCriticalCount,
        testCoverageLevel);
  }

  TrustCap trustCap(
      int rawScore,
      Boolean finalBuildPassed,
      int testsPassed,
      int testsTotal,
      int testsSkipped,
      String reviewerVerdictName,
      int unfixedCriticalCount,
      String testCoverageLevel) {
    int cap = 100;
    List<String> reasons = new ArrayList<>();

    if ("REVIEW_FAILED".equals(reviewerVerdictName)) {
      cap = Math.min(cap, 70);
      reasons.add("reviewer failed");
    } else if ("UNRESOLVED_ISSUES".equals(reviewerVerdictName)) {
      cap = Math.min(cap, 80);
      reasons.add("reviewer reported unresolved issues");
    }

    if (unfixedCriticalCount >= 3) {
      cap = Math.min(cap, 70);
      reasons.add(unfixedCriticalCount + " unresolved critical issues");
    } else if (unfixedCriticalCount >= 1) {
      cap = Math.min(cap, 85);
      reasons.add(unfixedCriticalCount + " unresolved critical issue(s)");
    }

    if (Boolean.FALSE.equals(finalBuildPassed)) {
      cap = Math.min(cap, 75);
      reasons.add("final build failed");
    }

    boolean successfulPopulation =
        testsPassed > 0 && testsSkipped >= 0 && (long) testsPassed + testsSkipped == testsTotal;
    if (testsTotal > 0 && !successfulPopulation) {
      cap = Math.min(cap, testsPassed <= 0 ? 70 : 85);
      reasons.add(
          testsPassed
              + "/"
              + testsTotal
              + " tests passed"
              + (testsSkipped > 0 ? "; " + testsSkipped + " skipped" : ""));
    } else if (testsTotal <= 0) {
      String level = testCoverageLevel == null ? null : testCoverageLevel.toUpperCase();
      if ("NONE".equals(level)) {
        cap = Math.min(cap, 75);
        reasons.add("test coverage was judged NONE");
      } else if ("UNKNOWN".equals(level)) {
        cap = Math.min(cap, 90);
        reasons.add("tests were not concretely measured");
      } else if (level == null) {
        cap = Math.min(cap, 90);
        reasons.add("tests were not measured");
      }
    }

    int boundedCap = Math.max(0, Math.min(100, cap));
    int score = Math.min(Math.max(0, Math.min(100, rawScore)), boundedCap);
    String reason = reasons.isEmpty() ? "no trust cap applied" : String.join("; ", reasons);
    return new TrustCap(score, boundedCap, reason);
  }
}
