package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
  @Mock private WorkspaceLockService workspaceLockService;
  @Mock private WorkspaceRestoreService workspaceRestoreService;
  @Mock private TierEnforcementService tierEnforcementService;
  @Mock private TierConfigRepository tierConfigRepository;
  @Mock private PublicationAdmissionService publicationAdmissionService;
  @Mock private BrownfieldHttpContractGuard brownfieldHttpContractGuard;

  private DeploymentService service;

  @TempDir Path tempDir;

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
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    PublicationAdmissionService.Admission admission = admissionWithLease(lease);
    when(publicationAdmissionService.assertEligible(run)).thenReturn(admission);
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
    verify(lease).close();
    verify(dockerfileGenerator, never()).detectAndGenerate(any());
    verify(deploymentRepository, never()).save(any());
    verifyNoInteractions(artifactRegistry, cloudRun, gitHubService, broadcaster);
  }

  @Test
  void asyncDeploymentRechecksAndClosesTheHandedOffPublicationAdmission() throws Exception {
    String runId = "run-deploy-async";
    PipelineRun run =
        PipelineRun.builder().runId(runId).status("COMPLETE").repoMode("NONE").build();
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    PublicationAdmissionService.Admission admission = admissionWithLease(lease);
    var strategy = mock(com.navneet.executespec.deployment.strategy.DeploymentStrategy.class);
    ReentrantLock workspaceLock = mock(ReentrantLock.class);

    when(stateStore.findRun(runId)).thenReturn(Optional.of(run));
    when(deploymentRepository.countActiveByRunId(runId)).thenReturn(0);
    when(deploymentRepository.countByRunId(runId)).thenReturn(0);
    when(agentProperties.getWorkspace()).thenReturn(tempDir.toString());
    when(publicationAdmissionService.assertEligible(run)).thenReturn(admission);
    when(dockerfileGenerator.detectAndGenerate(any())).thenReturn(strategy);
    when(strategy.type())
        .thenReturn(com.navneet.executespec.state.entity.Deployment.DeployStrategy.UNKNOWN);
    when(artifactRegistry.fullImageUri(any())).thenReturn("registry/image:tag");
    when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(workspaceLockService.getLock(runId)).thenReturn(workspaceLock);
    when(workspaceLock.tryLock(10, TimeUnit.SECONDS)).thenReturn(false);

    service.deploy(runId, RunRepo.RepoMode.NONE, null, null);

    verify(publicationAdmissionService).assertWorkspaceGenerationUnchanged(run, admission);
    verify(lease, timeout(3000)).assertOwned();
    verify(lease, timeout(3000)).close();
  }

  @Test
  void generatedDeploymentFilesCannotEscapeTheAdmittedGeneration() throws Exception {
    String runId = "run-deploy-generation";
    PipelineRun run = PipelineRun.builder().runId(runId).status("COMPLETE").build();
    PublicationAdmissionService.Admission admission =
        new PublicationAdmissionService.Admission("a".repeat(64), "PUBLICATION_ELIGIBLE");
    var strategy = mock(com.navneet.executespec.deployment.strategy.DeploymentStrategy.class);
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run));
    when(deploymentRepository.countActiveByRunId(runId)).thenReturn(0);
    when(agentProperties.getWorkspace()).thenReturn(tempDir.toString());
    when(publicationAdmissionService.assertEligible(run)).thenReturn(admission);
    when(dockerfileGenerator.detectAndGenerate(any())).thenReturn(strategy);
    doThrow(new IllegalStateException("PUBLICATION_EVIDENCE_STALE"))
        .when(publicationAdmissionService)
        .assertWorkspaceGenerationUnchanged(run, admission);
    when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var failed = service.deploy(runId, RunRepo.RepoMode.NONE, null, null);

    assertThat(failed.getStatus())
        .isEqualTo(com.navneet.executespec.state.entity.Deployment.DeployStatus.FAILED);
    assertThat(failed.getErrorMessage()).contains("PUBLICATION_EVIDENCE_STALE");
    verify(publicationAdmissionService).assertWorkspaceGenerationUnchanged(run, admission);
    verifyNoInteractions(artifactRegistry, cloudRun, gitHubService, broadcaster);
  }

  private static PublicationAdmissionService.Admission admissionWithLease(
      RunWorkspaceAuthorityService.Lease lease) throws Exception {
    var constructor =
        PublicationAdmissionService.Admission.class.getDeclaredConstructor(
            String.class, String.class, RunWorkspaceAuthorityService.Lease.class);
    constructor.setAccessible(true);
    return constructor.newInstance("a".repeat(64), "PUBLICATION_ELIGIBLE", lease);
  }
}
