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
    org.mockito.Mockito.doThrow(new IllegalStateException("BROWNFIELD_HTTP_CONTRACT_REMOVED"))
        .when(contracts)
        .assertCompatible(run.getRunId());

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
            contracts);

    assertThat(kickoff.generateValidationReport(run.getRunId(), null, null, null)).isEmpty();
    verify(validation, never())
        .generateAndPersist(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }
}
