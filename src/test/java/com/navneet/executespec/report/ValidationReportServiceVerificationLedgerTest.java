package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.report.model.ReportIssueDTO;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;
import com.navneet.executespec.scoring.ReviewScoringService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

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
            events::add);
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
            null);
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
    assertEquals(saved.get().getVerifiedScore(), event.verifiedScore());
    assertNotNull(event.runGoalDecision());
    assertEquals("UNCERTAIN", event.runGoalDecision().state().name());
    assertEquals(
        "run goal: Add a billing export endpoint with CSV download",
        event.runGoalDecision().goalSummary());
    assertEntry(event.verificationLedger(), "code-delivered", "PASS", "DETERMINISTIC", "HIGH");
    assertEntry(event.verificationLedger(), "build", "PASS", "DETERMINISTIC", "HIGH");
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
