package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.report.RunChangeEvidenceService;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.reviewer.AccuracyAssessmentService;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunValidationKickoffBrownfieldTest {

  @TempDir Path tempDir;

  @Test
  void incompatibleBrownfieldSurfaceStopsEveryValidationEntrypointBeforePersistence() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    ValidationReportService validation = mock(ValidationReportService.class);
    BrownfieldHttpContractGuard contracts = mock(BrownfieldHttpContractGuard.class);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(tempDir.toString());
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-brownfield-validation")
            .userRequest("Preserve POST /simulation/casino-game")
            .build();
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(contracts.hasBaseline(run.getRunId())).thenReturn(true);
    org.mockito.Mockito.doThrow(
            new BrownfieldHttpContractGuard.ContractCompatibilityException(
                "BROWNFIELD_HTTP_CONTRACT_REMOVED",
                "Publication blocked because existing HTTP contracts were removed: POST"
                    + " /simulation/casino-game"))
        .when(contracts)
        .assertCompatible(run.getRunId());
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    PerkyMessenger perky = mock(PerkyMessenger.class);

    RunValidationKickoff kickoff =
        new RunValidationKickoff(
            runRepo,
            mock(AgentTaskRepository.class),
            properties,
            mock(ModelResolver.class),
            mock(AccuracyAssessmentService.class),
            validation,
            mock(RunChangeEvidenceService.class),
            mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
            mock(com.navneet.executespec.deployment.preview.verify.PreviewVerifier.class),
            mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
            mock(RunLlmOperationLifecycleService.class),
            mock(CorrectionWorkspaceTransaction.class),
            mock(WorkflowConfigEvidenceInspector.class),
            mock(DeterministicHttpRequirementVerifier.class),
            contracts,
            perky,
            new RunValidationFailureReporter(runRepo, broadcaster, new ContextSanitizer()),
            mock(
                com.navneet.executespec.workspacegeneration.WorkspaceGenerationRecoveryService
                    .class));

    assertThat(kickoff.generateValidationReport(run.getRunId(), null, null, null)).isEmpty();
    assertThat(run.getFailureReason())
        .isEqualTo(
            "VALIDATION_FAILED[BROWNFIELD_HTTP_CONTRACT_REMOVED]: Publication blocked because"
                + " existing HTTP contracts were removed: POST /simulation/casino-game");
    assertThat(kickoff.persistedDeterministicFailureCode(run.getRunId()))
        .isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
    verify(runRepo, org.mockito.Mockito.atLeastOnce()).save(run);
    verify(perky).start(run.getRunId(), "VALIDATING");
    org.mockito.ArgumentCaptor<RunEvent> event =
        org.mockito.ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emit(org.mockito.ArgumentMatchers.eq(run.getRunId()), event.capture());
    assertThat(event.getValue().type()).isEqualTo("VALIDATION_FAILED");
    assertThat(event.getValue().data())
        .containsEntry("code", "BROWNFIELD_HTTP_CONTRACT_REMOVED")
        .containsEntry(
            "reason",
            "Publication blocked because existing HTTP contracts were removed: POST"
                + " /simulation/casino-game")
        .containsEntry("phase", "validation")
        .containsEntry("publicationBlocked", true);
    verify(validation, never())
        .generateAndPersist(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void missingDeterministicHttpRequirementPersistsTerminalCodeBeforeReportPersistence() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    ValidationReportService validation = mock(ValidationReportService.class);
    DeterministicHttpRequirementVerifier verifier =
        mock(DeterministicHttpRequirementVerifier.class);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(tempDir.toString());
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-http-requirement-validation")
            .status("WORKING")
            .userRequest("Implement POST /items")
            .build();
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    org.mockito.Mockito.doThrow(
            new IllegalStateException(
                "DETERMINISTIC_HTTP_REQUIREMENT_MISSING: required HTTP routes are absent: POST"
                    + " /items"))
        .when(verifier)
        .assertSatisfied(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(run.getUserRequest()),
            org.mockito.ArgumentMatchers.any(Path.class));

    RunValidationKickoff kickoff =
        new RunValidationKickoff(
            runRepo,
            mock(AgentTaskRepository.class),
            properties,
            mock(ModelResolver.class),
            mock(AccuracyAssessmentService.class),
            validation,
            mock(RunChangeEvidenceService.class),
            mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
            mock(com.navneet.executespec.deployment.preview.verify.PreviewVerifier.class),
            mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
            mock(RunLlmOperationLifecycleService.class),
            mock(CorrectionWorkspaceTransaction.class),
            mock(WorkflowConfigEvidenceInspector.class),
            verifier,
            mock(BrownfieldHttpContractGuard.class),
            mock(PerkyMessenger.class),
            new RunValidationFailureReporter(
                runRepo, mock(SseBroadcaster.class), new ContextSanitizer()),
            mock(
                com.navneet.executespec.workspacegeneration.WorkspaceGenerationRecoveryService
                    .class));

    assertThat(kickoff.generateValidationReport(run.getRunId(), null, null, null)).isEmpty();
    String failureCode = kickoff.persistedDeterministicFailureCode(run.getRunId());
    assertThat(failureCode).isEqualTo("DETERMINISTIC_HTTP_REQUIREMENT_MISSING");
    assertThat(RunValidationKickoff.unavailableValidationTerminalStatus(failureCode))
        .isEqualTo("FAILED");
    assertThat(run.getFailureReason())
        .isEqualTo(
            "VALIDATION_FAILED[DETERMINISTIC_HTTP_REQUIREMENT_MISSING]: required HTTP routes are"
                + " absent: POST /items");
    verify(runRepo, org.mockito.Mockito.atLeastOnce()).save(run);
    verify(validation, never())
        .generateAndPersist(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unprovableForStackRequirementIsRecordedAsEvidenceNotAsValidationFailure() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    ValidationReportService validation = mock(ValidationReportService.class);
    DeterministicHttpRequirementVerifier verifier =
        mock(DeterministicHttpRequirementVerifier.class);
    RunChangeEvidenceService changeEvidence = mock(RunChangeEvidenceService.class);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(tempDir.toString());
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-node-http-requirement")
            .status("WORKING")
            .userRequest("Expose GET /api/items/low-stock")
            .build();
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(verifier.assertSatisfied(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(run.getUserRequest()),
            org.mockito.ArgumentMatchers.any(Path.class)))
        .thenReturn(
            DeterministicHttpRequirementVerifier.Verification.unprovableForStack(
                java.util.Set.of(
                    new com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey(
                        "GET", "/api/items/low-stock")),
                java.util.Set.of()));
    when(changeEvidence.inspect(
            org.mockito.ArgumentMatchers.eq(run.getRunId()),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            new RunChangeEvidenceService.ChangeEvidence(
                false, "no baseline", null, java.util.List.of(), java.util.List.of()));
    when(validation.generateAndPersist(
            org.mockito.ArgumentMatchers.eq(run.getRunId()), org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Optional.of(com.navneet.executespec.report.entity.ValidationReport.builder().build()));

    RunValidationKickoff kickoff =
        new RunValidationKickoff(
            runRepo,
            mock(AgentTaskRepository.class),
            properties,
            mock(ModelResolver.class),
            mock(AccuracyAssessmentService.class),
            validation,
            changeEvidence,
            mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
            mock(com.navneet.executespec.deployment.preview.verify.PreviewVerifier.class),
            mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
            mock(RunLlmOperationLifecycleService.class),
            mock(CorrectionWorkspaceTransaction.class),
            mock(WorkflowConfigEvidenceInspector.class),
            verifier,
            mock(BrownfieldHttpContractGuard.class),
            mock(PerkyMessenger.class),
            new RunValidationFailureReporter(
                runRepo, mock(SseBroadcaster.class), new ContextSanitizer()),
            mock(
                com.navneet.executespec.workspacegeneration.WorkspaceGenerationRecoveryService
                    .class));

    assertThat(kickoff.generateValidationReport(run.getRunId(), null, null, null)).isPresent();

    // Not a failure: no VALIDATION_FAILED diagnostic and no deterministic failure code persisted.
    assertThat(run.getFailureReason()).isNull();
    assertThat(kickoff.persistedDeterministicFailureCode(run.getRunId())).isNull();

    // Evidence: the persisted report input carries the unprovable-for-stack ledger note.
    org.mockito.ArgumentCaptor<com.navneet.executespec.report.model.ReportInput> input =
        org.mockito.ArgumentCaptor.forClass(com.navneet.executespec.report.model.ReportInput.class);
    verify(validation)
        .generateAndPersist(org.mockito.ArgumentMatchers.eq(run.getRunId()), input.capture());
    assertThat(input.getValue().review().getIssues())
        .anySatisfy(
            issue -> {
              assertThat(issue.getId()).isEqualTo("SYNTH-HTTP-STACK-UNPROVABLE");
              assertThat(issue.getSeverity())
                  .isEqualTo(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR);
              assertThat(issue.getDescription()).contains("GET /api/items/low-stock");
            });
  }

  @Test
  void deterministicRequirementFailureRetainsItsMachineCodeAndReadableReason() {
    var diagnostic =
        RunValidationFailureReporter.diagnostic(
            new IllegalStateException(
                "DETERMINISTIC_HTTP_REQUIREMENT_MISSING: required HTTP routes are absent: POST"
                    + " /items"),
            new ContextSanitizer());

    assertThat(diagnostic.code()).isEqualTo("DETERMINISTIC_HTTP_REQUIREMENT_MISSING");
    assertThat(diagnostic.reason()).isEqualTo("required HTTP routes are absent: POST /items");
    assertThat(diagnostic.summary())
        .isEqualTo(
            "VALIDATION_FAILED[DETERMINISTIC_HTTP_REQUIREMENT_MISSING]: required HTTP routes are"
                + " absent: POST /items");
  }

  @Test
  void validationDiagnosticRedactsSecretsAndIgnoresUnrelatedPersistedFailures() {
    var diagnostic =
        RunValidationFailureReporter.diagnostic(
            new IllegalStateException(
                "VALIDATION_PROVIDER_FAILED: Bearer sk-proj-12345678901234567890"),
            new ContextSanitizer());
    assertThat(diagnostic.reason()).contains("[REDACTED]").doesNotContain("sk-proj-");

    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    PipelineRun run = PipelineRun.builder().runId("run-1").failureReason("unrelated").build();
    when(runRepo.findById("run-1")).thenReturn(Optional.of(run));
    RunValidationFailureReporter reporter =
        new RunValidationFailureReporter(
            runRepo, mock(SseBroadcaster.class), new ContextSanitizer());
    assertThat(reporter.persistedReasonWhenUnavailable("run-1", "VALIDATION_REPORT_UNAVAILABLE"))
        .isNull();
  }

  @Test
  void newValidationAttemptCannotReusePriorDeterministicFailureCode() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-stale")
            .failureReason(
                "VALIDATION_FAILED[DETERMINISTIC_HTTP_REQUIREMENT_MISSING]: old missing route")
            .build();
    when(runRepo.findById("run-stale")).thenReturn(Optional.of(run));
    RunValidationFailureReporter reporter =
        new RunValidationFailureReporter(
            runRepo, mock(SseBroadcaster.class), new ContextSanitizer());

    reporter.beginAttempt("run-stale");
    reporter.reportUnavailable(
        "run-stale",
        "VALIDATION_RESULT_NOT_PERSISTED",
        "Validation completed without a durable report",
        "validation");

    assertThat(reporter.persistedDeterministicFailureCode("run-stale")).isNull();
    assertThat(run.getFailureReason())
        .isEqualTo(
            "VALIDATION_FAILED[VALIDATION_RESULT_NOT_PERSISTED]: Validation completed without a"
                + " durable report");
  }
}
