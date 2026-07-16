package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ValidationReportServiceConcreteRescoreTest {

  @Test
  void concreteTestsRecomputeHeadlinesAndPublishFreshEvidence() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    var events = mock(org.springframework.context.ApplicationEventPublisher.class);
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-1")
            .runId("run-1")
            .filesGenerated(4)
            .pipelineResult(PipelineResult.PASSED)
            .finalBuildPassed(true)
            .testsPassed(0)
            .testsTotal(0)
            .reviewerVerdict("CLEAN")
            .verifiedScore(76)
            .rubricScore(80)
            .bootVerdict(Verdict.BUILD_FAILED.name())
            .bootHealthy(false)
            .bootReason("image/build step failed")
            .issuesJson("[]")
            .accuracyJson(
                "{\"specAdherenceScore\":90,"
                    + "\"testCoverageLevel\":\"PARTIAL\","
                    + "\"endpointCoverage\":{\"coveragePercent\":100}}")
            .build();
    when(repository.findByRunId("run-1")).thenReturn(Optional.of(report));
    when(repository.save(any(ValidationReport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(flags.isEnabled(any())).thenReturn(true);

    ValidationReportService service =
        new ValidationReportService(
            repository,
            null,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            flags,
            events);

    Optional<ValidationReport> result =
        service.rescoreAfterConcreteTests(
            "run-1", TestExecutionSummary.builder().totalTests(10).passed(8).failed(2).build());

    assertTrue(result.isPresent());
    assertEquals(10, report.getTestsTotal());
    assertEquals(8, report.getTestsPassed());
    assertEquals(69, report.getVerifiedScore());
    assertEquals(96, report.getRubricScore());
    assertEquals(Verdict.BUILD_FAILED.name(), report.getBootVerdict());
    assertFalse(report.getBootHealthy());
    verify(repository).save(report);
    var eventCaptor = org.mockito.ArgumentCaptor.forClass(ValidationReportEvidenceEvent.class);
    verify(events).publishEvent(eventCaptor.capture());
    ValidationReportEvidenceEvent event = eventCaptor.getValue();
    assertEquals(69, event.verifiedScore());
    assertEquals(96, event.rubricScore());
    VerificationEvidenceDTO tests =
        event.verificationLedger().stream()
            .filter(e -> "tests".equals(e.id()))
            .findFirst()
            .orElseThrow();
    assertEquals("8/10 tests passed.", tests.summary());
    VerificationEvidenceDTO boot =
        event.verificationLedger().stream()
            .filter(e -> "boot-verification".equals(e.id()))
            .findFirst()
            .orElseThrow();
    assertEquals("FAIL", boot.status());
  }

  @Test
  void missingReportIsSafeAndDoesNotInventEvidence() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(repository.findByRunId("missing")).thenReturn(Optional.empty());
    ValidationReportService service =
        new ValidationReportService(repository, null, new ObjectMapper(), null, null, null, flags);

    assertTrue(
        service.rescoreAfterConcreteTests("missing", TestExecutionSummary.empty()).isEmpty());
  }

  @Test
  void concreteRescoreCannotInflatePersistedExecutableFailure() {
    ValidationReportRepository repository = mock(ValidationReportRepository.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    ValidationReport report =
        ValidationReport.builder()
            .id("vr-e2")
            .runId("run-e2")
            .filesGenerated(2)
            .pipelineResult(PipelineResult.FAILED)
            .finalBuildPassed(true)
            .reviewerVerdict("CLEAN")
            .verifiedScore(0)
            .rubricScore(0)
            .executableVerificationStatus("ASSERTION_FAILED")
            .executableTestsRun(true)
            .executableTestsPassed(false)
            .issuesJson("[]")
            .accuracyJson("{\"testCoverageLevel\":\"FULL\"}")
            .build();
    when(repository.findByRunId("run-e2")).thenReturn(Optional.of(report));
    when(repository.save(any(ValidationReport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ValidationReportService service =
        new ValidationReportService(
            repository,
            null,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            flags,
            mock(org.springframework.context.ApplicationEventPublisher.class));

    service.rescoreAfterConcreteTests(
        "run-e2", TestExecutionSummary.builder().totalTests(10).passed(10).build());

    assertEquals(0, report.getVerifiedScore());
    assertEquals(0, report.getRubricScore());
    assertEquals(PipelineResult.FAILED, report.getPipelineResult());
    assertEquals("ASSERTION_FAILED", report.getExecutableVerificationStatus());
  }
}
