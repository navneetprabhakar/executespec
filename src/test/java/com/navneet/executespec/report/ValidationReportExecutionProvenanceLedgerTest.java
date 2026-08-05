package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test evidence records both freshness and exercised boundary. Fresh workspace suites remain
 * deterministic evidence, while only live-preview functional execution can prove product journeys.
 *
 * <p>The three-valued contract is the whole point: {@code null} (no signal recorded — every
 * pre-V317 row, and every producer that establishes no freshness) must read as UNPROVEN, never as
 * proven-false and never as proven-true.
 */
class ValidationReportExecutionProvenanceLedgerTest {

  private ValidationReportService svc;

  @BeforeEach
  void setUp() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    svc = new ValidationReportService(null, null, new ObjectMapper(), null, null, null, flags);
  }

  private static ValidationReport.ValidationReportBuilder greenReport() {
    return ValidationReport.builder()
        .id("vr-prov")
        .runId("run-prov")
        .filesGenerated(3)
        .pipelineResult(PipelineResult.PASSED)
        .finalBuildPassed(true)
        .testsPassed(6)
        .testsTotal(6)
        .reviewerVerdict("CLEAN")
        .verifiedScore(95)
        .rubricScore(95);
  }

  private VerificationEvidenceDTO row(ValidationReport report, String id) {
    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());
    return ledger.stream()
        .filter(r -> id.equals(r.id()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no ledger row " + id));
  }

  @Test
  void functionalExecutionRendersFunctionalDeterministicHigh() {
    VerificationEvidenceDTO tests =
        row(
            greenReport()
                .testExecutionVerified(Boolean.TRUE)
                .testEvidenceScope("LIVE_PREVIEW_FUNCTIONAL")
                .build(),
            "tests");

    assertEquals("PASS", tests.status());
    assertEquals("DETERMINISTIC_FUNCTIONAL", tests.source());
    assertEquals("HIGH", tests.confidence());
  }

  @Test
  void workspaceSuiteIsDeterministicButExplicitlyNotFunctional() {
    VerificationEvidenceDTO tests =
        row(
            greenReport()
                .testExecutionVerified(Boolean.TRUE)
                .testEvidenceScope("WORKSPACE_TEST_SUITE")
                .build(),
            "tests");

    assertEquals("PASS", tests.status());
    assertEquals("DETERMINISTIC_WORKSPACE_SUITE", tests.source());
    assertEquals("HIGH", tests.confidence());
    assertEquals(
        RunContinuationDecision.STOP_BLOCKED,
        new ValidationReportGoalProofEvaluator()
            .evaluate(
                "run-prov",
                PipelineResult.PASSED,
                svc.buildVerificationLedger(
                    greenReport()
                        .testExecutionVerified(Boolean.TRUE)
                        .testEvidenceScope("WORKSPACE_TEST_SUITE")
                        .build(),
                    List.of()))
            .recommendedDecision());
    assertEquals(
        "UNPROVEN",
        new ValidationReportGoalProofEvaluator()
                .evidenceReferences(
                    "COMPLETE",
                    PipelineResult.PASSED,
                    svc.buildVerificationLedger(
                        greenReport()
                            .testExecutionVerified(Boolean.TRUE)
                            .testEvidenceScope("WORKSPACE_TEST_SUITE")
                            .build(),
                        List.of()))
                .stream()
                .filter(reference -> "tests".equals(reference.evidenceId()))
                .findFirst()
                .orElseThrow()
                .proofStatus());
  }

  @Test
  void nullProvenanceRendersUnverifiedCount() {
    // Pre-V317 rows and WorkspaceTestRunner paths that record no signal land here.
    VerificationEvidenceDTO tests = row(greenReport().build(), "tests");

    assertEquals("UNKNOWN", tests.status());
    assertEquals("UNVERIFIED_COUNT", tests.source());
    assertEquals("MEDIUM", tests.confidence());
  }

  @Test
  void explicitlyUnprovenExecutionRendersUnverifiedCount() {
    VerificationEvidenceDTO tests =
        row(greenReport().testExecutionVerified(Boolean.FALSE).build(), "tests");

    assertEquals("UNKNOWN", tests.status());
    assertEquals("UNVERIFIED_COUNT", tests.source());
    assertEquals("MEDIUM", tests.confidence());
  }

  @Test
  void unavailableTestsStayUnverifiedEvenWhenProvenanceIsTrue() {
    // Nothing was executed, so there is nothing for provenance to vouch for.
    VerificationEvidenceDTO tests =
        row(
            greenReport().testsPassed(0).testsTotal(0).testExecutionVerified(Boolean.TRUE).build(),
            "tests");

    assertEquals("UNKNOWN", tests.status());
    assertEquals("UNVERIFIED_COUNT", tests.source());
  }

  @Test
  void countDerivedExecutableVerificationInheritsTheTestProvenance() {
    ValidationReport unproven =
        greenReport()
            .executableTestsRun(Boolean.TRUE)
            .executableVerificationStatus("PASSED")
            .build();
    assertEquals("UNVERIFIED_COUNT", row(unproven, "executable-verification").source());

    ValidationReport proven =
        greenReport()
            .executableTestsRun(Boolean.TRUE)
            .executableVerificationStatus("PASSED")
            .testExecutionVerified(Boolean.TRUE)
            .testEvidenceScope("LIVE_PREVIEW_FUNCTIONAL")
            .build();
    assertEquals("DETERMINISTIC_FUNCTIONAL", row(proven, "executable-verification").source());
    assertEquals("HIGH", row(proven, "executable-verification").confidence());
  }

  @Test
  void nullProvenanceDoesNotReachStopDoneOnTheTestsRowAlone() {
    // RR-1660 explicitly requires verifying the goal-proof consequence rather than assuming it.
    // Neither missing counts nor a forged PASS with weak provenance may carry STOP_DONE.
    ValidationReport report = greenReport().testsPassed(0).testsTotal(0).build();
    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());

    var decision =
        new ValidationReportGoalProofEvaluator()
            .evaluate("run-prov", PipelineResult.PASSED, ledger);

    assertNotNull(decision);
    assertNotEquals(RunContinuationDecision.STOP_DONE, decision.recommendedDecision());
  }
}
