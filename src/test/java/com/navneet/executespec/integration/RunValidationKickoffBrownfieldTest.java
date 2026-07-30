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
            new RunValidationFailureReporter(runRepo, broadcaster, new ContextSanitizer()));

    assertThat(kickoff.generateValidationReport(run.getRunId(), null, null, null)).isEmpty();
    assertThat(run.getFailureReason())
        .isEqualTo(
            "VALIDATION_FAILED[BROWNFIELD_HTTP_CONTRACT_REMOVED]: Publication blocked because"
                + " existing HTTP contracts were removed: POST /simulation/casino-game");
    verify(runRepo).save(run);
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
}
