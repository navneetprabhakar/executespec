package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.CommandExecutionEvidence;
import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.report.model.ReportIssueDTO;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
import com.navneet.executespec.report.model.WorkflowConfigEvidence.FileEvidence;
import com.navneet.executespec.report.model.WorkflowConfigEvidence.ParseStatus;
import com.navneet.executespec.report.model.WorkflowConfigEvidence.Status;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.reviewer.model.ReviewVerdict;
import com.navneet.executespec.scoring.ReviewScoringService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestExecutionSummary.EvidenceScope;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * RL-2026-06 — pins the report evidence ledger. verifiedScore remains the operational routing
 * number, but the product surface must expose which claims are deterministic vs LLM/self-assessed.
 */
class ValidationReportServiceVerificationLedgerTest {

  private FeatureFlagService flags;
  private ValidationReportService svc;

  @BeforeEach
  void setUp() {
    flags = mock(FeatureFlagService.class);
    svc = new ValidationReportService(null, null, new ObjectMapper(), null, null, null, flags);
  }

  @Test
  void ledger_separates_deterministic_and_llm_signals() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-1")
            .runId("run-1")
            .workspaceGeneration("a".repeat(64))
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
                  "specEvidenceStatus": "CITED",
                  "specEvidenceLocations": ["src/App.tsx:10-30"],
                  "endpointEvidenceStatus": "CITED",
                  "endpointEvidenceLocations": ["src/App.tsx:10-30"],
                  "testEvidenceStatus": "CITED",
                  "testEvidenceLocations": ["src/App.test.tsx:5-18"],
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
    // RR-1646: the persisted test counts come from runner report files read off disk with no
    // exit-code check, so they cannot claim DETERMINISTIC/HIGH — the one label that asserts
    // independence from an LLM. Status is unchanged; only the provenance claim is weakened.
    assertEntry(ledger, "tests", "UNKNOWN", "UNVERIFIED_COUNT", "MEDIUM");
    assertEntry(ledger, "reviewer", "PASS", "LLM_REVIEWER", "MEDIUM");
    assertEntry(ledger, "spec-adherence", "PASS", "LLM_ASSESSMENT", "LOW");
    assertEntry(ledger, "endpoint-coverage", "WARN", "LLM_ASSISTED_COUNT", "MEDIUM");
    assertEntry(ledger, "test-coverage-judgment", "WARN", "LLM_ASSESSMENT", "LOW");

    VerificationEvidenceDTO score = find(ledger, "score-summary");
    assertEquals("INTERNAL_FORMULA", score.source());
    assertTrue(score.summary().contains("Treat this as a summary, not independent proof."));
    assertTrue(
        ledger.stream().allMatch(row -> "a".repeat(64).equals(row.workspaceGeneration())),
        "every evidence row must inherit the persisted report generation");
  }

  @Test
  void unknownRequirementsKeepSpecProofAndRunGoalUncertain() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    StateStore stateStore = mock(StateStore.class);
    ValidationReportService service =
        new ValidationReportService(
            repository, null, new ObjectMapper(), null, stateStore, null, flags);
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-unknown")
            .runId("run-unknown")
            .filesGenerated(3)
            .pipelineResult(PipelineResult.PASSED)
            .issuesJson("[]")
            .finalBuildPassed(true)
            .testsPassed(12)
            .testsTotal(12)
            .reviewerVerdict("CLEAN")
            .verifiedScore(89)
            .accuracyJson(
                """
                {
                  "specAdherenceScore": 89,
                  "overallAccuracyGrade": "B",
                  "specEvidenceStatus": "CITED",
                  "specEvidenceLocations": ["src/AuthService.java:20-42"],
                  "unknownRequirements": [
                    "Could not verify that caller identity comes from an authenticated principal"
                  ]
                }
                """)
            .build();
    when(repository.findByRunId("run-unknown")).thenReturn(Optional.of(report));
    when(stateStore.findRun("run-unknown"))
        .thenReturn(
            Optional.of(
                PipelineRun.builder()
                    .runId("run-unknown")
                    .userRequest("Build an authenticated inventory reservation API")
                    .build()));

    var dto = service.getByRunId("run-unknown").orElseThrow();

    assertEntry(dto.verificationLedger(), "spec-adherence", "WARN", "LLM_ASSESSMENT", "LOW");
    assertTrue(
        find(dto.verificationLedger(), "spec-adherence")
            .summary()
            .contains("1 requirement(s) unverified"));
    assertNotNull(dto.runGoalDecision());
    assertEquals("UNCERTAIN", dto.runGoalDecision().state().name());
    assertEquals(
        "CONTINUE_SAME_MODEL_PROVIDER", dto.runGoalDecision().recommendedDecision().name());
    assertTrue(
        dto.goalProofReferences().stream()
            .anyMatch(
                reference ->
                    "spec-adherence".equals(reference.evidenceId())
                        && "BLOCKING".equals(reference.proofStatus())
                        && "LLM_ASSESSMENT".equals(reference.source())));
    assertEquals(84, dto.verifiedScore());
    assertEquals("WARN", find(dto.verificationLedger(), "score-summary").status());
  }

  @Test
  void uncitedModelAssessmentCannotPresentPassOrFailAsSourceBackedEvidence() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-uncited")
            .runId("run-uncited")
            .runtimeSurface("HTTP")
            .accuracyJson(
                """
                {
                  "specAdherenceScore": 100,
                  "testCoverageLevel": "COMPREHENSIVE",
                  "evidenceStatus": "UNCITED",
                  "evidenceLocations": [],
                  "endpointCoverage": {"coveragePercent": 100}
                }
                """)
            .build();

    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());

    assertEntry(ledger, "spec-adherence", "UNKNOWN", "LLM_ASSESSMENT", "LOW");
    assertEntry(ledger, "endpoint-coverage", "UNKNOWN", "LLM_ASSISTED_COUNT", "MEDIUM");
    assertEntry(ledger, "test-coverage-judgment", "UNKNOWN", "LLM_ASSESSMENT", "LOW");
    assertTrue(find(ledger, "spec-adherence").artifact().startsWith("UNAVAILABLE:"));
  }

  @Test
  void heuristicAccuracyFallbackIsLabelledInternalFormulaWithoutANumericGrade() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-heuristic")
            .runId("run-heuristic")
            .runtimeSurface("HTTP")
            .accuracyJson(
                """
{
  "specAdherenceScore": 75,
  "overallAccuracyGrade": "C",
  "gradingNotes": "Accuracy assessment unavailable — estimated from review issue count.",
  "testCoverageLevel": "PARTIAL"
}
""")
            .build();

    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());

    assertEntry(ledger, "spec-adherence", "UNKNOWN", "INTERNAL_FORMULA", "LOW");
    VerificationEvidenceDTO row = find(ledger, "spec-adherence");
    assertTrue(row.summary().contains("assessment unavailable"));
    assertTrue(row.summary().contains("heuristic estimate"));
    assertNull(row.observed());
    assertNull(row.total());
  }

  @Test
  void citationForOneDimensionCannotAuthenticateTheOtherDimensions() {
    ValidationReport report =
        ValidationReport.builder()
            .runtimeSurface("HTTP")
            .accuracyJson(
                """
                {
                  "specAdherenceScore": 100,
                  "specEvidenceStatus": "CITED",
                  "specEvidenceLocations": ["src/App.java:1-5"],
                  "endpointCoverage": {"coveragePercent": 100},
                  "testCoverageLevel": "COMPREHENSIVE",
                  "endpointEvidenceStatus": "UNCITED",
                  "testEvidenceStatus": "UNCITED"
                }
                """)
            .build();

    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());

    assertEntry(ledger, "spec-adherence", "PASS", "LLM_ASSESSMENT", "LOW");
    assertEntry(ledger, "endpoint-coverage", "UNKNOWN", "LLM_ASSISTED_COUNT", "MEDIUM");
    assertEntry(ledger, "test-coverage-judgment", "UNKNOWN", "LLM_ASSESSMENT", "LOW");
  }

  @Test
  void extracted_builder_matches_service_compatibility_delegate() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-builder")
            .runId("run-builder")
            .filesGenerated(1)
            .pipelineResult(PipelineResult.PASSED)
            .finalBuildPassed(true)
            .testsPassed(1)
            .testsTotal(1)
            .reviewerVerdict("CLEAN")
            .verifiedScore(100)
            .accuracyJson(
                "{\"specAdherenceScore\":100,\"testCoverageLevel\":\"GOOD\","
                    + "\"endpointCoverage\":{\"coveragePercent\":100}}")
            .build();
    List<ReportIssueDTO> issues =
        List.of(new ReportIssueDTO("lint", "WOULD_FAIL_LINT", null, "REVIEWER_AGENT", null));
    ValidationVerificationLedgerBuilder builder =
        new ValidationVerificationLedgerBuilder(new ObjectMapper());

    assertEquals(svc.buildVerificationLedger(report, issues), builder.build(report, issues));
  }

  @Test
  void missingIndependentTestsExplainWhyWorkspaceTestsAreNotProof() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-tests-pending")
            .runId("run-tests-pending")
            .filesGenerated(2)
            .pipelineResult(PipelineResult.NEEDS_ATTENTION)
            .finalBuildPassed(true)
            .testsPassed(0)
            .testsTotal(0)
            .testExecutionVerified(false)
            .reviewerVerdict("CLEAN")
            .verifiedScore(70)
            .build();

    var tests =
        new ValidationVerificationLedgerBuilder(new ObjectMapper())
            .build(report, List.of()).stream()
                .filter(entry -> entry.id().equals("tests"))
                .findFirst()
                .orElseThrow();

    assertEquals("UNKNOWN", tests.status());
    assertTrue(tests.claim().contains("Worker-authored workspace tests are diagnostic"));
    assertTrue(
        tests.summary().contains("Independent end-to-end/functional verification is pending"));
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
    // RR-1646: same provenance downgrade; confidence was already MEDIUM for an unmeasured row.
    assertEntry(ledger, "tests", "UNKNOWN", "UNVERIFIED_COUNT", "MEDIUM");
    assertEntry(ledger, "reviewer", "UNKNOWN", "LLM_REVIEWER", "MEDIUM");
    assertEntry(ledger, "spec-adherence", "UNKNOWN", "LLM_ASSESSMENT", "LOW");
    assertEntry(ledger, "endpoint-coverage", "UNKNOWN", "LLM_ASSISTED_COUNT", "LOW");
  }

  @Test
  void ledgerMarksEndpointCoverageNotApplicableForCliRuntime() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-cli")
            .runId("run-cli")
            .runtimeSurface("CLI")
            .filesGenerated(2)
            .pipelineResult(PipelineResult.PASSED)
            .build();

    List<VerificationEvidenceDTO> ledger = svc.buildVerificationLedger(report, List.of());

    assertEntry(ledger, "endpoint-coverage", "N/A", "NOT_APPLICABLE", "HIGH");
    assertTrue(find(ledger, "endpoint-coverage").summary().contains("CLI"));
  }

  @Test
  void generatedCliReportOmitsEndpointCoverageFromBothScoreFormulas() throws Exception {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    ValidationReportGenerator generator = mock(ValidationReportGenerator.class);
    ReviewScoringService reviewScoringService = mock(ReviewScoringService.class);
    when(flags.isEnabled("scoring.additive-v2")).thenReturn(true);
    when(flags.isEnabled("scoring.gaming-guards")).thenReturn(true);
    ValidationReportService service =
        new ValidationReportService(
            repository,
            generator,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            reviewScoringService,
            flags);
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-cli-score")
            .userRequest("Build a command-line todo manager")
            .planJson("{\"runtimeSurface\":\"CLI\"}")
            .build();
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(100)
            .overallAccuracyGrade("A")
            .testCoverageLevel("GOOD")
            // Adversarial stale/model output: this HTTP-only signal must be ignored for CLI.
            .endpointCoverage(AccuracyReport.EndpointCoverage.builder().coveragePercent(0).build())
            .build();
    ReviewReport review =
        ReviewReport.builder()
            .verdict(ReviewVerdict.APPROVED)
            .issues(List.of())
            .accuracyReport(accuracy)
            .build();
    TestExecutionSummary tests =
        TestExecutionSummary.builder()
            .totalTests(4)
            .passed(3)
            .skipped(1)
            .executionVerified(true)
            .evidenceScope(EvidenceScope.LIVE_PREVIEW_FUNCTIONAL)
            .build();
    ReportInput input =
        new ReportInput(
            run,
            List.of(
                WorkerReport.builder()
                    .filesCreated(List.of("src/main/java/example/TodoCli.java"))
                    .filesModified(List.of())
                    .build()),
            review,
            CompileResult.passed(1),
            List.of(),
            tests,
            List.of(),
            false,
            null,
            null,
            "e".repeat(64),
            null,
            null,
            null);
    when(generator.generate(input))
        .thenReturn(new ValidationReportGenerator.LlmReportResult("# CLI report", "model-a", 0, 0));
    when(reviewScoringService.score(any(), any(), any(), any(), any()))
        .thenReturn(new ReviewScoringService.Scores(null, null, null, null, null, null));
    when(repository.findByRunId("run-cli-score")).thenReturn(Optional.empty());
    when(repository.save(any(ValidationReport.class))).thenAnswer(inv -> inv.getArgument(0));

    ValidationReport saved = service.generateAndPersist("run-cli-score", input).orElseThrow();

    assertEquals("CLI", saved.getRuntimeSurface());
    assertEquals(3, saved.getTestsPassed());
    assertEquals(1, saved.getTestsSkipped());
    assertEquals(4, saved.getTestsTotal());
    assertEquals(Boolean.TRUE, saved.getExecutableTestsPassed());
    assertEquals(100, saved.getVerifiedScore());
    assertEquals(100, saved.getRubricScore());
    assertEntry(
        service.buildVerificationLedger(saved, List.of()),
        "endpoint-coverage",
        "N/A",
        "NOT_APPLICABLE",
        "HIGH");
  }

  @Test
  void staleReportDtoSuppressesPreviouslyComputedPipelineResult() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-stale")
            .runId("run-stale")
            .status("STALE")
            .filesGenerated(2)
            .pipelineResult(PipelineResult.PASSED)
            .finalBuildPassed(true)
            .testsPassed(8)
            .testsTotal(8)
            .reviewerVerdict("CLEAN")
            .verifiedScore(100)
            .rubricScore(100)
            .reportMarkdown("# Superseded report")
            .issuesJson("[]")
            .build();
    when(repository.findByRunId("run-stale")).thenReturn(Optional.of(report));
    ValidationReportService service =
        new ValidationReportService(repository, null, new ObjectMapper(), null, null, null, flags);

    var dto = service.getByRunId("run-stale").orElseThrow();

    assertEquals("STALE", dto.reportStatus());
    assertEquals(null, dto.pipelineResult());
    assertEquals(-1, dto.filesGenerated());
    assertEquals(null, dto.finalBuildPassed());
    assertEquals(null, dto.verifiedScore());
    assertEquals(null, dto.reportMarkdown());
    assertTrue(dto.verificationLedger().isEmpty());
    assertEquals("UNCERTAIN", dto.runGoalDecision().state().name());
  }

  @Test
  void ledgerBindsZeroToTheExactDeterministicGate() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-zero-bound")
            .runId("run-zero-bound")
            .workspaceGeneration("d".repeat(64))
            .filesGenerated(0)
            .pipelineResult(PipelineResult.FAILED)
            .verifiedScore(0)
            .scoreZeroCauseJson(
                zeroCauseJson(
                    "ADDITIVE_V2",
                    "CODE_DELIVERED",
                    "validation_report.files_generated",
                    "No approved product-code diff was captured."))
            .build();

    VerificationEvidenceDTO cause =
        find(svc.buildVerificationLedger(report, List.of()), "score-zero-cause");

    assertEquals("FAIL", cause.status());
    assertEquals("DETERMINISTIC", cause.source());
    assertEquals("HIGH", cause.confidence());
    assertTrue(cause.summary().contains("No approved product-code diff was captured"));
    assertEquals("validation_report.files_generated", cause.artifact());
    assertEquals("d".repeat(64), cause.workspaceGeneration());
  }

  @Test
  void ledgerDoesNotInventACauseForHistoricalOrLlmAttributedZero() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-zero-unattributed")
            .runId("run-zero-unattributed")
            .workspaceGeneration("f".repeat(64))
            .filesGenerated(4)
            .pipelineResult(PipelineResult.NEEDS_ATTENTION)
            // A nearby deterministic failure must not be claimed as causal when the historical
            // LLM cap itself could have produced this zero.
            .finalBuildPassed(false)
            .testsPassed(4)
            .testsTotal(4)
            .reviewerVerdict("CLEAN")
            .verifiedScore(0)
            .accuracyJson(
                """
                {
                  "specAdherenceScore": 0,
                  "missingRequirements": ["LLM-only finding"]
                }
                """)
            .build();

    VerificationEvidenceDTO cause =
        find(svc.buildVerificationLedger(report, List.of()), "score-zero-cause");

    assertEquals("UNKNOWN", cause.status());
    assertEquals("INTERNAL_FORMULA", cause.source());
    assertEquals("LOW", cause.confidence());
    assertTrue(cause.summary().contains("Deterministic cause unavailable"));
    assertTrue(cause.summary().contains("no cause was inferred from LLM"));
  }

  @Test
  void ledgerUsesOnlyProvenTestExecutionAsAZeroCause() {
    ValidationReport proven =
        ValidationReport.builder()
            .id("vr-zero-tests")
            .runId("run-zero-tests")
            .workspaceGeneration("e".repeat(64))
            .filesGenerated(2)
            .pipelineResult(PipelineResult.FAILED)
            .finalBuildPassed(true)
            .testsPassed(0)
            .testsTotal(5)
            .testExecutionVerified(true)
            .verifiedScore(0)
            .scoreZeroCauseJson(
                zeroCauseJson(
                    "MULTIPLICATIVE_LEGACY",
                    "TESTS",
                    "validation_report.tests_passed/tests_total/test_execution_verified",
                    "The concrete test execution passed 0/5."))
            .build();

    VerificationEvidenceDTO provenCause =
        find(svc.buildVerificationLedger(proven, List.of()), "score-zero-cause");
    assertEquals("DETERMINISTIC", provenCause.source());
    assertTrue(provenCause.summary().contains("concrete test execution passed 0/5"));

    proven.setTestExecutionVerified(false);
    proven.setScoreZeroCauseJson(null);
    VerificationEvidenceDTO unprovenCause =
        find(svc.buildVerificationLedger(proven, List.of()), "score-zero-cause");
    assertEquals("UNKNOWN", unprovenCause.status());
    assertTrue(unprovenCause.summary().contains("cause unavailable"));
  }

  @Test
  void ledgerRejectsMalformedOrUnsupportedPersistedZeroCause() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-zero-malformed")
            .runId("run-zero-malformed")
            .filesGenerated(0)
            .finalBuildPassed(false)
            .verifiedScore(0)
            .scoreZeroCauseJson("{not-json")
            .build();

    assertEquals(
        "UNKNOWN",
        find(svc.buildVerificationLedger(report, List.of()), "score-zero-cause").status());
    report.setScoreZeroCauseJson(
        zeroCauseJson("ADDITIVE_V2", "MADE_UP", "validation_report.files_generated", "Invented."));
    assertEquals(
        "UNKNOWN",
        find(svc.buildVerificationLedger(report, List.of()), "score-zero-cause").status());
  }

  @Test
  void ledgerTreatsLegacyUnknownFileCountAsUnattributed() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-zero-unknown-files")
            .runId("run-zero-unknown-files")
            .workspaceGeneration("9".repeat(64))
            .filesGenerated(-1)
            .pipelineResult(PipelineResult.FAILED)
            .verifiedScore(0)
            .build();

    VerificationEvidenceDTO cause =
        find(svc.buildVerificationLedger(report, List.of()), "score-zero-cause");

    assertEquals("UNKNOWN", cause.status());
    assertTrue(cause.summary().contains("cause unavailable"));
    assertEquals(
        "UNKNOWN", find(svc.buildVerificationLedger(report, List.of()), "code-delivered").status());
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

  @Test
  void ledger_trust_cap_ignores_noncritical_blocking_issue() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-5")
            .runId("run-5")
            .filesGenerated(3)
            .pipelineResult(PipelineResult.PASSED)
            .finalBuildPassed(true)
            .testsPassed(10)
            .testsTotal(10)
            .reviewerVerdict("CLEAN")
            .verifiedScore(100)
            .accuracyJson("{\"specAdherenceScore\":100,\"testCoverageLevel\":\"GOOD\"}")
            .build();

    // This remains visible in the report-issues ledger, but must not be treated as an unresolved
    // CRITICAL for the score trust cap.
    List<VerificationEvidenceDTO> ledger =
        svc.buildVerificationLedger(
            report,
            List.of(
                new ReportIssueDTO(
                    "minor review note",
                    "WOULD_FAIL_REVIEW",
                    "Add a test",
                    "REVIEWER_AGENT",
                    null)));

    assertEntry(ledger, "report-issues", "FAIL", "HYBRID", "MEDIUM");
    VerificationEvidenceDTO cap = find(ledger, "trust-cap");
    assertEquals(75, cap.total());
    assertTrue(cap.summary().contains("independent functional verification is unavailable"));
    assertFalse(cap.summary().contains("unresolved critical"));
  }

  @Test
  void ledger_does_not_count_successfully_auto_fixed_visibility_issue_as_open() {
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-fixed")
            .runId("run-fixed")
            .filesGenerated(3)
            .pipelineResult(PipelineResult.PASSED_WITH_FIXES)
            .finalBuildPassed(true)
            .testsPassed(10)
            .testsTotal(10)
            .reviewerVerdict("FIXED_ISSUES")
            .verifiedScore(95)
            .accuracyJson("{\"specAdherenceScore\":95,\"testCoverageLevel\":\"GOOD\"}")
            .build();

    List<VerificationEvidenceDTO> ledger =
        svc.buildVerificationLedger(
            report,
            List.of(
                new ReportIssueDTO(
                    "Reviewer corrected route wiring",
                    "WOULD_FAIL_REVIEW",
                    "Auto-fixed successfully",
                    "REVIEWER_AGENT",
                    null)));

    assertEntry(ledger, "report-issues", "PASS", "HYBRID", "MEDIUM");
  }

  @Test
  void generateAndPersistPublishesValidationEvidenceEventAfterSave() throws Exception {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    ValidationReportGenerator generator = mock(ValidationReportGenerator.class);
    ReviewScoringService reviewScoringService = mock(ReviewScoringService.class);
    StateStore stateStore = mock(StateStore.class);
    List<Object> events = new ArrayList<>();
    ValidationReportService service =
        new ValidationReportService(
            repository,
            generator,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            stateStore,
            reviewScoringService,
            flags,
            events::add,
            null);
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .userRequest("Add a billing export endpoint with CSV download")
            .build();
    WorkerReport workerReport =
        WorkerReport.builder()
            .filesCreated(List.of("src/App.tsx"))
            .filesModified(List.of())
            .build();
    ReportInput input =
        new ReportInput(
            run,
            List.of(workerReport),
            null,
            CompileResult.passed(1),
            List.of(),
            null,
            List.of(),
            false,
            null,
            new BootVerification(Verdict.HEALTHY, true, "boots; /health responded 200"),
            "b".repeat(64),
            new WorkflowConfigEvidence(
                Status.PASSED,
                List.of(
                    new FileEvidence(
                        ".github/workflows/ci.yml", "c".repeat(64), ParseStatus.VALID, null)),
                "All workflow files parsed"),
            new CommandExecutionEvidence("./mvnw verify", "/workspace/run-1", 0, "BUILD SUCCESS"),
            new CommandExecutionEvidence(
                "./mvnw test", "/workspace/run-1", 0, "Tests run: 12, Failures: 0"));
    when(generator.generate(input))
        .thenReturn(new ValidationReportGenerator.LlmReportResult("# Report", "model-a", 0, 0));
    when(reviewScoringService.score(any(), any(), any(), any(), any()))
        .thenReturn(new ReviewScoringService.Scores(null, null, null, null, null, null));
    when(repository.findByRunId("run-1")).thenReturn(Optional.empty());
    when(repository.save(org.mockito.ArgumentMatchers.any(ValidationReport.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    Optional<ValidationReport> saved = service.generateAndPersist("run-1", input);

    assertTrue(saved.isPresent());
    assertFalse(events.isEmpty(), "expected a validation evidence event");
    ValidationReportEvidenceEvent event =
        assertInstanceOf(ValidationReportEvidenceEvent.class, events.get(0));
    assertEquals("run-1", event.runId());
    assertEquals(saved.get().getId(), event.reportId());
    assertEquals("b".repeat(64), saved.get().getWorkspaceGeneration());
    assertEquals("b".repeat(64), event.workspaceGeneration());
    assertTrue(
        event.verificationLedger().stream()
            .allMatch(row -> "b".repeat(64).equals(row.workspaceGeneration())));
    assertEquals(saved.get().getVerifiedScore(), event.verifiedScore());
    assertNotNull(event.runGoalDecision());
    assertEquals("UNCERTAIN", event.runGoalDecision().state().name());
    assertEquals(
        "run goal: Add a billing export endpoint with CSV download",
        event.runGoalDecision().goalSummary());
    assertEntry(event.verificationLedger(), "code-delivered", "PASS", "DETERMINISTIC", "HIGH");
    assertEntry(event.verificationLedger(), "build", "PASS", "DETERMINISTIC", "HIGH");
    assertEntry(
        event.verificationLedger(),
        "workflow-config",
        "PASS",
        "DETERMINISTIC_CONFIG_PARSER",
        "HIGH");
    assertEntry(event.verificationLedger(), "boot-verification", "PASS", "DETERMINISTIC", "HIGH");
    assertEntry(
        event.verificationLedger(), "executable-verification", "WARN", "DETERMINISTIC", "HIGH");
    assertEquals(Verdict.HEALTHY.name(), saved.get().getBootVerdict());
    assertTrue(saved.get().getBootHealthy());
    assertEquals("boots; /health responded 200", saved.get().getBootReason());
    assertFalse(saved.get().getExecutableTestsRun());
    assertEquals("BUILD_ONLY", saved.get().getExecutableVerificationStatus());
    assertEquals(null, saved.get().getExecutionFailureStage());
    assertTrue(saved.get().getExecutableEvidenceJson().contains("\"buildPassed\":true"));
    assertEquals("./mvnw verify", saved.get().getBuildCommand());
    assertEquals("/workspace/run-1", saved.get().getBuildWorkingDirectory());
    assertEquals(0, saved.get().getBuildExitCode());
    assertEquals("BUILD SUCCESS", saved.get().getBuildOutput());
    assertEquals("./mvnw test", saved.get().getTestCommand());
    assertEquals("/workspace/run-1", saved.get().getTestWorkingDirectory());
    assertEquals(0, saved.get().getTestExitCode());
    assertEquals("Tests run: 12, Failures: 0", saved.get().getTestOutput());
    VerificationEvidenceDTO workflowRow =
        event.verificationLedger().stream()
            .filter(row -> "workflow-config".equals(row.id()))
            .findFirst()
            .orElseThrow();
    assertTrue(workflowRow.summary().contains(".github/workflows/ci.yml@" + "c".repeat(12)));
    VerificationEvidenceDTO buildRow =
        event.verificationLedger().stream()
            .filter(row -> "build".equals(row.id()))
            .findFirst()
            .orElseThrow();
    assertTrue(buildRow.artifact().contains("command=./mvnw verify"));
    assertTrue(buildRow.artifact().contains("cwd=/workspace/run-1"));
    assertTrue(buildRow.artifact().contains("exit=0"));
    assertNotNull(run.getRunGoalDonenessJson());
    assertTrue(run.getRunGoalDonenessJson().contains("\"state\":\"UNCERTAIN\""));
    assertTrue(
        run.getRunGoalDonenessJson()
            .contains("run goal: Add a billing export endpoint with CSV download"));
    verify(stateStore).save(run);
  }

  @Test
  void reportDtoIncludesRunGoalDecisionDerivedFromLedger() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    StateStore stateStore = mock(StateStore.class);
    ValidationReportService service =
        new ValidationReportService(
            repository, null, new ObjectMapper(), null, stateStore, null, flags);
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-4")
            .runId("run-4")
            .filesGenerated(1)
            .languages("TypeScript")
            .pipelineResult(PipelineResult.PASSED)
            .issuesJson("[]")
            .checksPassed("COMPILATION")
            .reportMarkdown("# Report")
            .finalBuildPassed(true)
            .testsPassed(2)
            .testsTotal(2)
            .testExecutionVerified(true)
            .testEvidenceScope("LIVE_PREVIEW_FUNCTIONAL")
            .reviewerVerdict("CLEAN")
            .verifiedScore(96)
            .accuracyJson("{\"specAdherenceScore\": 91}")
            .build();
    when(repository.findByRunId("run-4")).thenReturn(Optional.of(report));
    when(stateStore.findRun("run-4"))
        .thenReturn(
            Optional.of(
                PipelineRun.builder()
                    .runId("run-4")
                    .userRequest("Generate a project setup guide")
                    .build()));

    var dto = service.getByRunId("run-4").orElseThrow();

    assertNotNull(dto.runGoalDecision());
    assertEquals("DONE", dto.runGoalDecision().state().name());
    assertEquals("STOP_DONE", dto.runGoalDecision().recommendedDecision().name());
    assertEquals("run goal: Generate a project setup guide", dto.runGoalDecision().goalSummary());
    assertTrue(
        dto.goalProofReferences().stream()
            .anyMatch(
                reference ->
                    "pipeline-result".equals(reference.evidenceId())
                        && "PROVEN".equals(reference.proofStatus())
                        && "validation_report.pipeline_result".equals(reference.artifact())));
  }

  @Test
  void terminalRefreshPersistsTheSameGoalDecisionProjectedByRunReport() throws Exception {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    StateStore stateStore = mock(StateStore.class);
    ObjectMapper mapper = new ObjectMapper();
    ValidationReportService service =
        new ValidationReportService(repository, null, mapper, null, stateStore, null, flags);
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-terminal")
            .runId("run-terminal")
            .status("COMPLETE")
            .filesGenerated(1)
            .pipelineResult(PipelineResult.PASSED)
            .issuesJson("[]")
            .finalBuildPassed(true)
            .testsPassed(1)
            .testsTotal(1)
            .testExecutionVerified(true)
            .testEvidenceScope("LIVE_PREVIEW_FUNCTIONAL")
            .reviewerVerdict("CLEAN")
            .verifiedScore(95)
            .accuracyJson("{\"specAdherenceScore\":95}")
            .build();
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-terminal")
            .status("COMPLETE")
            .userRequest("Render a visible browser heading")
            .runGoalDonenessJson("{\"reason\":\"run lifecycle is REVIEWING\"}")
            .build();
    when(repository.findByRunId("run-terminal")).thenReturn(Optional.of(report));
    when(stateStore.findRun("run-terminal")).thenReturn(Optional.of(run));

    service.refreshRunGoalDecisionAfterTerminal("run-terminal");
    var projected = service.getByRunId("run-terminal").orElseThrow().runGoalDecision();

    var persisted = mapper.readTree(run.getRunGoalDonenessJson());
    assertEquals(projected.state().name(), persisted.path("state").asText());
    assertEquals(
        projected.recommendedDecision().name(), persisted.path("recommendedDecision").asText());
    assertEquals(projected.goalSummary(), persisted.path("goalSummary").asText());
    assertFalse(run.getRunGoalDonenessJson().contains("REVIEWING"));
    verify(stateStore).save(run);
  }

  @Test
  void sharedReportCarriesScoresAndEvidenceLedger() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-share")
            .runId("run-share")
            .shareTokenHash(DigestUtils.sha256Hex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .shareExpiresAt(Instant.now().plusSeconds(3600))
            .filesGenerated(2)
            .pipelineResult(PipelineResult.PASSED)
            .issuesJson("[]")
            .finalBuildPassed(true)
            .testsPassed(2)
            .testsTotal(2)
            .reviewerVerdict("CLEAN")
            .verifiedScore(100)
            .rubricScore(100)
            .bootVerdict(Verdict.HEALTHY.name())
            .bootHealthy(true)
            .bootReason("boots; /health responded 200")
            .build();
    when(repository.findByShareTokenHash(report.getShareTokenHash()))
        .thenReturn(Optional.of(report));
    ValidationReportService service =
        new ValidationReportService(repository, null, new ObjectMapper(), null, null, null, flags);

    var shared =
        service.getByShareToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").orElseThrow();

    assertEquals(100, shared.verifiedScore());
    assertEquals(100, shared.rubricScore());
    assertEquals(true, shared.finalBuildPassed());
    assertEquals(2, shared.testsPassed());
    assertEquals(2, shared.testsTotal());
    assertEquals("CLEAN", shared.reviewerVerdict());
    assertEntry(shared.verificationLedger(), "boot-verification", "PASS", "DETERMINISTIC", "HIGH");
  }

  @Test
  void legacySharedReportRejectsExpiredAndMalformedBearers() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    String token = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    ValidationReport expired =
        ValidationReport.builder()
            .id("vr-expired")
            .runId("run-expired")
            .shareTokenHash(DigestUtils.sha256Hex(token))
            .shareExpiresAt(Instant.now().minusSeconds(1))
            .build();
    when(repository.findByShareTokenHash(expired.getShareTokenHash()))
        .thenReturn(Optional.of(expired));
    ValidationReportService service =
        new ValidationReportService(repository, null, new ObjectMapper(), null, null, null, flags);

    assertTrue(service.getByShareToken(token).isEmpty());
    assertTrue(service.getByShareToken("short").isEmpty());
  }

  @Test
  void legacySharedReportRejectsStaleEvidence() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    String token = "ccccccccccccccccccccccccccccccccccccccccccc";
    ValidationReport stale =
        ValidationReport.builder()
            .id("vr-stale-share")
            .runId("run-stale-share")
            .status("STALE")
            .shareTokenHash(DigestUtils.sha256Hex(token))
            .shareExpiresAt(Instant.now().plusSeconds(3600))
            .pipelineResult(PipelineResult.PASSED)
            .verifiedScore(100)
            .build();
    when(repository.findByShareTokenHash(stale.getShareTokenHash())).thenReturn(Optional.of(stale));
    ValidationReportService service =
        new ValidationReportService(repository, null, new ObjectMapper(), null, null, null, flags);

    assertTrue(service.getByShareToken(token).isEmpty());
  }

  /**
   * RR-1646 — the executable-verification row keeps DETERMINISTIC only while its status rests on
   * the build signal. Once the concrete-test counts decided the status, the row inherits their
   * unproven provenance and must say so.
   */
  @Test
  void executableVerificationRowInheritsUnprovenTestProvenanceWhenTestsDecidedIt() {
    ValidationReport testDriven =
        ValidationReport.builder()
            .id("vr-exec-tests")
            .runId("run-exec-tests")
            .filesGenerated(4)
            .pipelineResult(PipelineResult.PASSED)
            .finalBuildPassed(true)
            .testsPassed(3)
            .testsTotal(3)
            .reviewerVerdict("CLEAN")
            .issuesJson("[]")
            .executableTestsRun(true)
            .executableTestsPassed(true)
            .executableVerificationStatus("PASSED")
            .build();
    assertEntry(
        svc.buildVerificationLedger(testDriven, List.of()),
        "executable-verification",
        "UNKNOWN",
        "UNVERIFIED_COUNT",
        "MEDIUM");

    ValidationReport buildOnly =
        ValidationReport.builder()
            .id("vr-exec-build")
            .runId("run-exec-build")
            .filesGenerated(4)
            .pipelineResult(PipelineResult.PASSED)
            .finalBuildPassed(true)
            .reviewerVerdict("CLEAN")
            .issuesJson("[]")
            .executableTestsRun(false)
            .executableVerificationStatus("BUILD_ONLY")
            .build();
    assertEntry(
        svc.buildVerificationLedger(buildOnly, List.of()),
        "executable-verification",
        "WARN",
        "DETERMINISTIC",
        "HIGH");
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

  private static String zeroCauseJson(String formula, String gate, String artifact, String reason) {
    return """
{"schemaVersion":1,"formula":"%s","causes":[{"gate":"%s","artifact":"%s","reason":"%s"}]}
"""
        .formatted(formula, gate, artifact, reason)
        .trim();
  }
}
