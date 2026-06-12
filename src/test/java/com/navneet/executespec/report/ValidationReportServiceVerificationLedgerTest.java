package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.ReportIssueDTO;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

import org.junit.jupiter.api.Test;

/**
 * RL-2026-06 — pins the report evidence ledger. verifiedScore remains the operational routing
 * number, but the product surface must expose which claims are deterministic vs LLM/self-assessed.
 */
class ValidationReportServiceVerificationLedgerTest {

  private final ValidationReportService svc =
      new ValidationReportService(
          null, null, new ObjectMapper(), null, null, null, mock(FeatureFlagService.class));

  @Test
  void ledger_separates_deterministic_and_llm_signals() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-1")
            .runId("run-1")
            .filesGenerated(4)
            .pipelineResult(PipelineResult.PASSED)
            .finalBuildPassed(true)
            .testsPassed(7)
            .testsTotal(8)
            .reviewerVerdict("FIXED_ISSUES")
            .verifiedScore(91)
            .rubricScore(94)
            .accuracyScore(88)
            .accuracyGrade("B")
            .accuracyJson(
                """
                {
                  "specAdherenceScore": 88,
                  "overallAccuracyGrade": "B",
                  "testCoverageLevel": "PARTIAL",
                  "endpointCoverage": {"coveragePercent": 75}
                }
                """)
            .build();

    List<VerificationEvidenceDTO> ledger =
        svc.buildVerificationLedger(
            report,
            List.of(
                new ReportIssueDTO(
                    "lint only", "WOULD_FAIL_LINT", null, "REVIEWER_AGENT", "src/App.tsx")));

    assertEntry(ledger, "code-delivered", "PASS", "DETERMINISTIC", "HIGH");
    assertEntry(ledger, "build", "PASS", "DETERMINISTIC", "HIGH");
    assertEntry(ledger, "tests", "WARN", "DETERMINISTIC", "HIGH");
    assertEntry(ledger, "reviewer", "PASS", "LLM_REVIEWER", "MEDIUM");
    assertEntry(ledger, "spec-adherence", "PASS", "LLM_ASSESSMENT", "LOW");
    assertEntry(ledger, "endpoint-coverage", "WARN", "LLM_ASSISTED_COUNT", "MEDIUM");
    assertEntry(ledger, "test-coverage-judgment", "WARN", "LLM_ASSESSMENT", "LOW");

    VerificationEvidenceDTO score = find(ledger, "score-summary");
    assertEquals("INTERNAL_FORMULA", score.source());
    assertTrue(score.summary().contains("Treat this as a summary, not independent proof."));
  }

  @Test
  void ledger_marks_missing_delivery_and_unmeasured_tests_honestly() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-2")
            .runId("run-2")
            .filesGenerated(0)
            .pipelineResult(PipelineResult.FAILED)
            .finalBuildPassed(null)
            .testsPassed(0)
            .testsTotal(0)
            .reviewerVerdict(null)
            .verifiedScore(0)
            .build();

    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());

    assertEntry(ledger, "code-delivered", "FAIL", "DETERMINISTIC", "HIGH");
    assertEntry(ledger, "build", "UNKNOWN", "DETERMINISTIC", "MEDIUM");
    assertEntry(ledger, "tests", "UNKNOWN", "DETERMINISTIC", "MEDIUM");
    assertEntry(ledger, "reviewer", "UNKNOWN", "LLM_REVIEWER", "MEDIUM");
    assertEntry(ledger, "spec-adherence", "UNKNOWN", "LLM_ASSESSMENT", "LOW");
  }

  @Test
  void ledger_explains_trust_cap() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-3")
            .runId("run-3")
            .filesGenerated(3)
            .pipelineResult(PipelineResult.NEEDS_ATTENTION)
            .finalBuildPassed(true)
            .testsPassed(10)
            .testsTotal(10)
            .reviewerVerdict("REVIEW_FAILED")
            .verifiedScore(70)
            .build();

    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());

    VerificationEvidenceDTO cap = find(ledger, "trust-cap");
    assertEquals("WARN", cap.status());
    assertEquals("INTERNAL_FORMULA", cap.source());
    assertTrue(cap.summary().contains("reviewer failed"));
    assertEquals(70, cap.total());
  }

  private static void assertEntry(
      List<VerificationEvidenceDTO> ledger,
      String id,
      String status,
      String source,
      String confidence) {
    VerificationEvidenceDTO entry = find(ledger, id);
    assertEquals(status, entry.status(), id + " status");
    assertEquals(source, entry.source(), id + " source");
    assertEquals(confidence, entry.confidence(), id + " confidence");
  }

  private static VerificationEvidenceDTO find(List<VerificationEvidenceDTO> ledger, String id) {
    VerificationEvidenceDTO entry =
        ledger.stream().filter(e -> id.equals(e.id())).findFirst().orElse(null);
    assertNotNull(entry, "missing ledger entry " + id);
    return entry;
  }
}
