package com.navneet.executespec.report;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
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

  private static final String PROVEN_TEST_COUNT_SOURCE = "DETERMINISTIC";

  private static final String UNPROVEN_TEST_COUNT_CONFIDENCE = "MEDIUM";

  private static final String PROVEN_TEST_COUNT_CONFIDENCE = "HIGH";

  /** True only when V317 recorded a positive execution-provenance proof for these counts. */
  private static boolean testCountsProven(ValidationReport entity) {
    return Boolean.TRUE.equals(entity.getTestExecutionVerified());
  }

  private static String testCountSource(ValidationReport entity) {
    return testCountsProven(entity) ? PROVEN_TEST_COUNT_SOURCE : UNPROVEN_TEST_COUNT_SOURCE;
  }

  private static String testCountConfidence(ValidationReport entity) {
    return testCountsProven(entity) ? PROVEN_TEST_COUNT_CONFIDENCE : UNPROVEN_TEST_COUNT_CONFIDENCE;
  }

  private final ObjectMapper objectMapper;

  ValidationVerificationLedgerBuilder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  List<VerificationEvidenceDTO> build(ValidationReport entity, List<ReportIssueDTO> issues) {
    List<VerificationEvidenceDTO> out = new ArrayList<>();

    out.add(
        evidence(
            "code-delivered",
            "Code delivered",
            "CODE",
            entity.getFilesGenerated() > 0 ? "PASS" : "FAIL",
            "DETERMINISTIC",
            "HIGH",
            "The run produced source changes.",
            entity.getFilesGenerated() > 0
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
            "validation_report.final_build_passed",
            build == null ? null : build ? 1 : 0,
            build == null ? null : 1));

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
      out.add(
          evidence(
              "executable-verification",
              "Executable verification",
              "VERIFICATION",
              switch (executableStatus) {
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
    int total = testsTotal == null ? 0 : testsTotal;
    int passed = testsPassed == null ? 0 : testsPassed;
    out.add(
        evidence(
            "tests",
            "Tests",
            "TESTS",
            total <= 0 ? "UNKNOWN" : passed == total ? "PASS" : passed > 0 ? "WARN" : "FAIL",
            total <= 0 ? UNPROVEN_TEST_COUNT_SOURCE : testCountSource(entity),
            total <= 0 ? UNPROVEN_TEST_COUNT_CONFIDENCE : testCountConfidence(entity),
            total > 0 && testCountsProven(entity)
                ? "Generated or workspace tests passed. The counts are bound to this run's own"
                    + " execution: report files were reset before launch, the runner's exit status"
                    + " was checked, and every parsed report postdates the launch."
                : "Generated or workspace tests passed. Counts are read from persisted test-runner"
                    + " output; the report carries no execution-freshness token, so a stale or"
                    + " absent run cannot be ruled out.",
            total <= 0
                ? "No concrete test execution was captured."
                : passed + "/" + total + " tests passed.",
            "validation_report.tests_passed/tests_total",
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

    AccuracyFacts accuracy = parseAccuracyFacts(entity.getAccuracyJson());
    out.add(
        evidence(
            "spec-adherence",
            "Spec adherence",
            "SPEC",
            accuracy.score() == null
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
            "validation_report.accuracy_json",
            accuracy.score(),
            accuracy.score() == null ? null : 100));

    out.add(
        evidence(
            "endpoint-coverage",
            "Endpoint coverage",
            "ENDPOINTS",
            accuracy.endpointCoverage() == null
                ? "UNKNOWN"
                : accuracy.endpointCoverage() >= 95
                    ? "PASS"
                    : accuracy.endpointCoverage() >= 70 ? "WARN" : "FAIL",
            "LLM_ASSISTED_COUNT",
            accuracy.endpointCoverage() == null ? "LOW" : "MEDIUM",
            "Expected API endpoints are present in the generated implementation.",
            accuracy.endpointCoverage() == null
                ? "No endpoint coverage evidence was captured; the rubric's N/A policy may still"
                    + " award this dimension."
                : "Endpoint coverage: " + accuracy.endpointCoverage() + "%.",
            "validation_report.accuracy_json.endpointCoverage",
            accuracy.endpointCoverage(),
            accuracy.endpointCoverage() == null ? null : 100));

    if (accuracy.testCoverageLevel() != null) {
      String level = accuracy.testCoverageLevel();
      out.add(
          evidence(
              "test-coverage-judgment",
              "Test coverage judgment",
              "TESTS",
              switch (level.toUpperCase()) {
                case "GOOD", "COMPREHENSIVE" -> "PASS";
                case "PARTIAL" -> "WARN";
                case "NONE" -> "FAIL";
                default -> "UNKNOWN";
              },
              "LLM_ASSESSMENT",
              "LOW",
              "Test coverage matches what the spec implied should be tested.",
              "LLM test coverage judgment: " + level + ".",
              "validation_report.accuracy_json.testCoverageLevel",
              null,
              null));
    }

    TrustCap trustCap =
        trustCap(
            100,
            entity.getFinalBuildPassed(),
            passed,
            total,
            entity.getReviewerVerdict(),
            countUnfixedCriticalIssues(issues),
            accuracy.testCoverageLevel());
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
            entity.getVerifiedScore() == null
                ? "UNKNOWN"
                : entity.getVerifiedScore() >= 85
                    ? "PASS"
                    : entity.getVerifiedScore() >= 70 ? "WARN" : "FAIL",
            "INTERNAL_FORMULA",
            "MEDIUM",
            "The internal routing score summarizes the available evidence.",
            "verifiedScore="
                + (entity.getVerifiedScore() == null ? "n/a" : entity.getVerifiedScore())
                + (entity.getRubricScore() == null
                    ? ""
                    : ", rubricScore=" + entity.getRubricScore())
                + ". Treat this as a summary, not independent proof.",
            "validation_report.verified_score",
            entity.getVerifiedScore(),
            entity.getVerifiedScore() == null ? null : 100));

    return List.copyOf(out);
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
        id, label, category, status, source, confidence, claim, summary, artifact, observed, total);
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

  record AccuracyFacts(
      Integer score,
      String grade,
      Integer endpointCoverage,
      String testCoverageLevel,
      int unknownRequirements) {}

  AccuracyFacts parseAccuracyFacts(String accuracyJson) {
    if (accuracyJson == null || accuracyJson.isBlank()) {
      return new AccuracyFacts(null, null, null, null, 0);
    }
    try {
      JsonNode root = objectMapper.readTree(accuracyJson);
      Integer score =
          root.hasNonNull("specAdherenceScore") ? root.get("specAdherenceScore").asInt() : null;
      String grade =
          root.hasNonNull("overallAccuracyGrade")
              ? root.get("overallAccuracyGrade").asText()
              : null;
      JsonNode endpoint = root.path("endpointCoverage");
      Integer endpointCoverage =
          endpoint.hasNonNull("coveragePercent") ? endpoint.get("coveragePercent").asInt() : null;
      String testCoverage =
          root.hasNonNull("testCoverageLevel") ? root.get("testCoverageLevel").asText() : null;
      JsonNode unknown = root.path("unknownRequirements");
      int unknownCount = unknown.isArray() ? unknown.size() : 0;
      return new AccuracyFacts(score, grade, endpointCoverage, testCoverage, unknownCount);
    } catch (Exception e) {
      log.warn("Failed to parse accuracy facts for verification ledger: {}", e.getMessage());
      return new AccuracyFacts(null, null, null, null, 0);
    }
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

    if (testsTotal > 0 && testsPassed < testsTotal) {
      cap = Math.min(cap, testsPassed <= 0 ? 70 : 85);
      reasons.add(testsPassed + "/" + testsTotal + " tests passed");
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
