package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.*;
import com.navneet.executespec.router.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectArchitectureJobServiceTest {
  private ProjectArchitectureJobStore store;
  private ProjectRepository projects;
  private ProjectRepoLinkRepository links;
  private ProjectRepoRepository repos;
  private ScmCredentialResolver credentials;
  private GitHubRepoService github;
  private LlmExecutionOperationCoordinator operationCoordinator;
  private ProjectProviderResolver providers;
  private ModelResolver models;
  private ProjectArchitectureJobService service;
  private UUID repoId;

  @BeforeEach
  void setUp() {
    store = mock(ProjectArchitectureJobStore.class);
    projects = mock(ProjectRepository.class);
    links = mock(ProjectRepoLinkRepository.class);
    repos = mock(ProjectRepoRepository.class);
    credentials = mock(ScmCredentialResolver.class);
    github = mock(GitHubRepoService.class);
    operationCoordinator = mock(LlmExecutionOperationCoordinator.class);
    providers = mock(ProjectProviderResolver.class);
    models = mock(ModelResolver.class);
    var properties = new ProjectArchitectureJobProperties();
    var admission =
        new ProjectArchitectureJobAdmission(store, projects, providers, models, properties);
    service =
        new ProjectArchitectureJobService(
            store,
            admission,
            links,
            repos,
            credentials,
            github,
            mock(LlmExecutionPolicyFactory.class),
            mock(com.navneet.executespec.executioncontext.LlmExecutionContextService.class),
            operationCoordinator,
            properties,
            new ObjectMapper());
    Project project =
        Project.builder()
            .id("project-1")
            .orgId("org-1")
            .createdBy("owner-1")
            .aiProvider(LlmProvider.GEMINI)
            .build();
    when(projects.findById("project-1")).thenReturn(Optional.of(project));
    when(providers.resolve(project)).thenReturn(LlmProvider.GEMINI);
    when(models.resolve("RepoArchitectureGenerator", LlmProvider.GEMINI))
        .thenReturn(route("gemini-architecture", AgentTier.PLANNING));
    when(models.resolve("ArchitectureDocGenerator", LlmProvider.GEMINI))
        .thenReturn(route("gemini-architecture", AgentTier.PLANNING));
    when(store.createOrGet(
            any(),
            eq("project-1"),
            eq("org-1"),
            eq("actor-1"),
            isNull(),
            eq("GEMINI"),
            eq("gemini-architecture"),
            eq("gemini-architecture"),
            eq(Duration.ofSeconds(7200))))
        .thenReturn(
            ProjectArchitectureJobFixture.job(
                ProjectArchitectureJobStore.Status.QUEUED, "PREPARING"));

    repoId = UUID.randomUUID();
    when(links.findByProjectId("project-1"))
        .thenReturn(
            List.of(
                ProjectRepoLink.builder()
                    .projectId("project-1")
                    .repoId(repoId)
                    .linkedBy("link-owner-1")
                    .build()));
    when(repos.findById(repoId))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder()
                    .id(repoId)
                    .scmProvider("GITHUB")
                    .sourceKey("acme/api")
                    .githubRepoFullName("acme/api")
                    .defaultBranch("main")
                    .build()));
  }

  @Test
  void admissionIsDbLocalAndDoesNotTouchCredentialOrScm() {
    service.request("project-1", "actor-1");

    verifyNoInteractions(credentials, github, links, repos);
  }

  @Test
  void missingProjectProviderRemainsAStableCustomerCorrectableConflict() {
    when(providers.resolve(any(Project.class)))
        .thenThrow(ProjectRoutingException.providerNotConfigured());

    assertThatThrownBy(() -> service.request("project-1", "actor-1"))
        .isInstanceOfSatisfying(
            ProjectRoutingException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.errorCode()).isEqualTo("PROJECT_PROVIDER_NOT_CONFIGURED");
            });
    verifyNoInteractions(store);
  }

  @Test
  void invalidServerModelMatrixReturnsStableServiceUnavailable() {
    when(models.resolve("RepoArchitectureGenerator", LlmProvider.GEMINI))
        .thenThrow(new ModelMatrixConfigurationException("missing server matrix row"));

    assertThatThrownBy(() -> service.request("project-1", "actor-1"))
        .isInstanceOfSatisfying(
            ProjectRoutingException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(error.errorCode()).isEqualTo("MODEL_MATRIX_UNAVAILABLE");
            });
    verifyNoInteractions(store);
  }

  @Test
  void preparingSnapshotsExactRevisionWithoutPersistingSecret() {
    when(credentials.require("link-owner-1", ScmProvider.GITHUB))
        .thenReturn(new ScmCredential(null, "top-secret-pat"));
    when(github.getBranchHeadSha("https://github.com/acme/api", "main", "top-secret-pat"))
        .thenReturn("1".repeat(40));
    var lease =
        new ProjectArchitectureJobStore.Lease(
            ProjectArchitectureJobFixture.job(
                ProjectArchitectureJobStore.Status.RUNNING, "PREPARING"),
            "worker-1",
            1,
            java.time.Instant.now().plusSeconds(900));

    var prepared = service.prepare(lease);

    assertThat(prepared.digest()).hasSize(64);
    assertThat(prepared.canonicalJson())
        .contains("\"revision\":\"" + "1".repeat(40) + "\"")
        .contains("\"credentialSubjectUserId\":\"link-owner-1\"")
        .doesNotContain("top-secret-pat");
  }

  @Test
  void architectureContextIdentityFitsExecutionContextColumnsAndBindsAllAttemptEvidence() {
    UUID jobId = UUID.fromString("4dfbcf24-3b91-4e8f-bda1-f31585082968");
    String digest = "a".repeat(64);

    String identity =
        ProjectArchitectureJobService.architectureContextId(jobId, digest, 1_785_286_800_000L);

    assertThat(identity).startsWith("architecture-job:").hasSize(49);
    assertThat(identity.length()).isLessThanOrEqualTo(96);
    assertThat(
            ProjectArchitectureJobService.architectureContextId(
                jobId, "b".repeat(64), 1_785_286_800_000L))
        .isNotEqualTo(identity);
    assertThat(
            ProjectArchitectureJobService.architectureContextId(jobId, digest, 1_785_286_800_001L))
        .isNotEqualTo(identity);
    assertThat(
            ProjectArchitectureJobService.architectureContextId(
                UUID.fromString("5dfbcf24-3b91-4e8f-bda1-f31585082968"),
                digest,
                1_785_286_800_000L))
        .isNotEqualTo(identity);
  }

  @Test
  void logicalOperationNeverAutoReplaysAnUncertainInvocation() {
    var job =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.RUNNING, "EXECUTING");
    Duration leaseDuration = Duration.ofMinutes(2);
    when(operationCoordinator.claimOrResume(any(), any(), eq("worker-1"), eq(leaseDuration)))
        .thenReturn(Optional.empty());

    assertThat(service.claimOperation(job, "worker-1", leaseDuration)).isEmpty();

    var descriptor = org.mockito.ArgumentCaptor.forClass(LlmExecutionOperationDescriptor.class);
    verify(operationCoordinator)
        .claimOrResume(any(), descriptor.capture(), eq("worker-1"), eq(leaseDuration));
    assertThat(descriptor.getValue().maxAttempts()).isEqualTo(1);
    verify(operationCoordinator, never()).beginNewInvocation(any(), any(), anyString(), any());
  }

  @Test
  void exactRouteCannotCoalesceDifferentActorAuthority() {
    when(store.createOrGet(
            any(),
            eq("project-1"),
            eq("org-1"),
            eq("other-actor"),
            isNull(),
            eq("GEMINI"),
            eq("pinned"),
            eq("pinned"),
            any()))
        .thenReturn(
            ProjectArchitectureJobFixture.job(
                ProjectArchitectureJobStore.Status.QUEUED, "PREPARING"));

    assertThatThrownBy(
            () ->
                service.requestExact(
                    "project-1", "other-actor", null, LlmProvider.GEMINI, "pinned"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            failure ->
                assertThat(((ResponseStatusException) failure).getStatusCode().value())
                    .isEqualTo(409));
  }

  @Test
  void durableAwaitDoesNotTreatQueuedAsComplete() {
    var admitted =
        ProjectArchitectureJobFixture.job(ProjectArchitectureJobStore.Status.QUEUED, "PREPARING");
    UUID resultId = UUID.randomUUID();
    var succeeded =
        ProjectArchitectureJobFixture.job(
            ProjectArchitectureJobStore.Status.SUCCEEDED, "PUBLISHING", resultId);
    when(store.get(admitted.id())).thenReturn(Optional.of(admitted), Optional.of(succeeded));

    assertThat(service.awaitArchitectureId(admitted)).isEqualTo(resultId);
    verify(store, times(2)).get(admitted.id());
  }

  private static ResolvedModel route(String model, AgentTier tier) {
    return ResolvedModel.builder()
        .requestedProvider(LlmProvider.GEMINI)
        .actualProvider(LlmProvider.GEMINI)
        .model(model)
        .agentTier(tier)
        .build();
  }
}
