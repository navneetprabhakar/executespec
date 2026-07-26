package com.navneet.executespec.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.GitHubScmRepositoryClient;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class ProjectRepoServiceProjectMemberTest {

  private final ProjectRepoRepository repoRepo = mock(ProjectRepoRepository.class);
  private final ProjectRepoLinkRepository linkRepo = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final ProjectMemberRepository projectMemberRepo = mock(ProjectMemberRepository.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final ProjectSourceFreshnessInvalidator freshnessInvalidator =
      mock(ProjectSourceFreshnessInvalidator.class);
  private final ProjectRepoService service =
      new ProjectRepoService(
          repoRepo,
          linkRepo,
          projectRepo,
          projectMemberRepo,
          mock(StackDetector.class),
          mock(GitHubRepoService.class),
          new ScmRepositoryClientRegistry(
              List.of(new GitHubScmRepositoryClient(mock(GitHubRepoService.class)))),
          mock(ScmCredentialResolver.class),
          userRepo,
          mock(TierEnforcementService.class),
          mock(GovernanceGuard.class),
          freshnessInvalidator,
          mock(ObjectProvider.class));

  @Test
  void listProjectReposAllowsInvitedMemberToSeeHostOrgLinkedRepo() {
    UUID repoId = UUID.randomUUID();
    Project project = Project.builder().id("project-1").orgId("host-org").name("P").build();
    ProjectRepo hostRepo =
        ProjectRepo.builder()
            .id(repoId)
            .scope(ProjectRepo.Scope.ORG)
            .ownerOrgId("host-org")
            .githubRepoFullName("host/repo")
            .githubOwner("host")
            .build();
    ProjectRepoLink link =
        ProjectRepoLink.builder()
            .id(UUID.randomUUID())
            .projectId("project-1")
            .repoId(repoId)
            .linkedBy("host-admin")
            .build();

    when(projectRepo.findById("project-1")).thenReturn(Optional.of(project));
    when(projectMemberRepo.existsByProjectIdAndUserId("project-1", "invitee")).thenReturn(true);
    when(linkRepo.findByProjectId("project-1")).thenReturn(List.of(link));
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(hostRepo));

    var repos = service.listProjectRepos("project-1", "invitee", "invitee-org");

    assertEquals(1, repos.size());
    assertEquals(repoId, repos.get(0).repoId());
    assertEquals("host/repo", repos.get(0).repo().githubRepoFullName());
  }

  @Test
  void listProjectReposRejectsDifferentOrgUserWithoutProjectMembership() {
    Project project = Project.builder().id("project-1").orgId("host-org").name("P").build();
    when(projectRepo.findById("project-1")).thenReturn(Optional.of(project));
    when(projectMemberRepo.existsByProjectIdAndUserId("project-1", "outsider")).thenReturn(false);

    assertThrows(
        ResponseStatusException.class,
        () -> service.listProjectRepos("project-1", "outsider", "other-org"));
  }

  @Test
  void linkInvalidatesTheExactRepoAndProjectFreshnessKeys() {
    UUID repoId = UUID.randomUUID();
    Project project = Project.builder().id("project-1").orgId("org-1").name("P").build();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .scope(ProjectRepo.Scope.USER)
            .ownerUserId("user-1")
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider("GITHUB")
            .githubRepoFullName("acme/widget")
            .sourceCapabilities(List.of("ATTACH"))
            .build();
    when(projectRepo.findById("project-1")).thenReturn(Optional.of(project));
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(linkRepo.findByProjectIdAndRepoId("project-1", repoId)).thenReturn(Optional.empty());

    service.linkToProject("project-1", repoId, "user-1", "org-1");

    verify(freshnessInvalidator).invalidateLink("project-1", repoId);
  }

  @Test
  void unlinkInvalidatesTheExactRepoAndProjectFreshnessKeys() {
    UUID repoId = UUID.randomUUID();
    Project project = Project.builder().id("project-1").orgId("org-1").name("P").build();
    when(projectRepo.findById("project-1")).thenReturn(Optional.of(project));
    when(linkRepo.findByProjectIdAndRepoId("project-1", repoId))
        .thenReturn(
            Optional.of(ProjectRepoLink.builder().projectId("project-1").repoId(repoId).build()));

    service.unlinkFromProject("project-1", repoId, "user-1", "org-1");

    verify(linkRepo).deleteByProjectIdAndRepoId("project-1", repoId);
    verify(freshnessInvalidator).invalidateLink("project-1", repoId);
  }
}
