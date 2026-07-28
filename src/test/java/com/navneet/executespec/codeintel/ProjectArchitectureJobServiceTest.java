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
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.*;
import com.navneet.executespec.router.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProjectArchitectureJobServiceTest {
  private ProjectArchitectureJobStore store;
  private ProjectRepository projects;
  private ProjectRepoLinkRepository links;
  private ProjectRepoRepository repos;
  private ScmCredentialResolver credentials;
  private GitHubRepoService github;
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
    ProjectProviderResolver providers = mock(ProjectProviderResolver.class);
    ModelResolver models = mock(ModelResolver.class);
    var properties = new ProjectArchitectureJobProperties();
    service =
        new ProjectArchitectureJobService(
            store,
            projects,
            links,
            repos,
            credentials,
            github,
            providers,
            models,
            mock(LlmExecutionPolicyFactory.class),
            mock(com.navneet.executespec.executioncontext.LlmExecutionContextService.class),
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
