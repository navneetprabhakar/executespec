package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.integration.scm.PublicationAdmissionService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRepo;
import com.navneet.executespec.state.repository.DeploymentRepository;
import com.navneet.executespec.state.repository.RunRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;

@ExtendWith(MockitoExtension.class)
class DeploymentContractPublicationAdmissionTest {

  @Mock private AgentProperties agentProperties;
  @Mock private DeploymentRepository deploymentRepository;
  @Mock private RunRepoRepository runRepoRepository;
  @Mock private DockerfileGenerator dockerfileGenerator;
  @Mock private ArtifactRegistryClient artifactRegistry;
  @Mock private CloudRunClient cloudRun;
  @Mock private DeploymentGitHubService gitHubService;
  @Mock private SseBroadcaster broadcaster;
  @Mock private StateStore stateStore;
  @Mock private MeterRegistry meterRegistry;
  @Mock private WorkspaceLockService workspaceLockService;
  @Mock private WorkspaceRestoreService workspaceRestoreService;
  @Mock private TierEnforcementService tierEnforcementService;
  @Mock private TierConfigRepository tierConfigRepository;
  @Mock private PublicationAdmissionService publicationAdmissionService;
  @Mock private BrownfieldHttpContractGuard brownfieldHttpContractGuard;

  private DeploymentService service;

  @BeforeEach
  void setUp() {
    DeploymentProperties properties =
        new DeploymentProperties(false, null, null, null, null, 0, null, null, null, null, null);
    service =
        new DeploymentService(
            properties,
            agentProperties,
            deploymentRepository,
            runRepoRepository,
            dockerfileGenerator,
            artifactRegistry,
            cloudRun,
            gitHubService,
            broadcaster,
            stateStore,
            meterRegistry,
            workspaceLockService,
            workspaceRestoreService,
            tierEnforcementService,
            tierConfigRepository,
            publicationAdmissionService,
            brownfieldHttpContractGuard);
  }

  @Test
  void incompatibleBrownfieldContractStopsBeforeAnyDeploymentSideEffect() throws Exception {
    String runId = "run-deploy-contract";
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .status("COMPLETE")
            .repoMode("EXISTING")
            .selectedRepoIds("[]")
            .build();
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run));
    when(deploymentRepository.countActiveByRunId(runId)).thenReturn(0);
    when(agentProperties.getWorkspace()).thenReturn("/tmp/executespec-deployment-contract");
    when(publicationAdmissionService.assertEligible(run))
        .thenReturn(
            new PublicationAdmissionService.Admission("a".repeat(64), "PUBLICATION_ELIGIBLE"));
    doThrow(
            new BrownfieldHttpContractGuard.ContractCompatibilityException(
                "BROWNFIELD_HTTP_CONTRACT_REMOVED",
                "Publication blocked because POST /simulation/casino-game was removed"))
        .when(brownfieldHttpContractGuard)
        .assertCompatible(runId);

    assertThatThrownBy(
            () ->
                service.deploy(
                    runId,
                    RunRepo.RepoMode.EXISTING,
                    "https://github.com/acme/customer",
                    "customer"))
        .isInstanceOf(BrownfieldHttpContractGuard.ContractCompatibilityException.class)
        .hasMessageContaining("POST /simulation/casino-game");

    var order = inOrder(publicationAdmissionService, brownfieldHttpContractGuard);
    order.verify(publicationAdmissionService).assertEligible(run);
    order.verify(brownfieldHttpContractGuard).assertCompatible(runId);
    verify(dockerfileGenerator, never()).detectAndGenerate(any());
    verify(deploymentRepository, never()).save(any());
    verifyNoInteractions(artifactRegistry, cloudRun, gitHubService, broadcaster);
  }
}
