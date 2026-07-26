package com.navneet.executespec.project.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.Test;

class ProjectSourceFreshnessInvalidatorTest {

  private final ProjectRepoRepository repoRepository = mock(ProjectRepoRepository.class);
  private final ProjectRepoLinkRepository linkRepository = mock(ProjectRepoLinkRepository.class);
  private final WorkspaceHealthService workspaceHealth = mock(WorkspaceHealthService.class);
  private final StalenessService staleness = mock(StalenessService.class);
  private final ProjectSourceFreshnessInvalidator invalidator =
      new ProjectSourceFreshnessInvalidator(
          repoRepository, linkRepository, workspaceHealth, staleness);

  @Test
  void repoHeadChangeInvalidatesOriginAndEveryExactLinkedProjectKey() {
    UUID repoId = UUID.randomUUID();
    when(linkRepository.findByRepoId(repoId))
        .thenReturn(
            List.of(
                ProjectRepoLink.builder().repoId(repoId).projectId("project-a").build(),
                ProjectRepoLink.builder().repoId(repoId).projectId("project-b").build(),
                ProjectRepoLink.builder().repoId(repoId).projectId("project-a").build()));

    invalidator.invalidateRepo(repoId);

    verify(workspaceHealth).invalidateOriginSha(repoId);
    verify(staleness).invalidate("project-a");
    verify(staleness).invalidate("project-b");
  }

  @Test
  void githubIdentityResolvesRegistryRepoBeforeDirectKeyInvalidation() {
    UUID repoId = UUID.randomUUID();
    when(repoRepository.findByGithubRepoFullNameIgnoreCase("acme/widget"))
        .thenReturn(
            List.of(ProjectRepo.builder().id(repoId).githubRepoFullName("acme/widget").build()));
    when(linkRepository.findByRepoId(repoId))
        .thenReturn(
            List.of(ProjectRepoLink.builder().repoId(repoId).projectId("project-a").build()));

    invalidator.invalidateGitHubRepo("acme/widget");

    verify(workspaceHealth).invalidateOriginSha(repoId);
    verify(staleness).invalidate("project-a");
  }
}
