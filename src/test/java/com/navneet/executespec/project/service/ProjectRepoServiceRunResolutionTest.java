package com.navneet.executespec.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class ProjectRepoServiceRunResolutionTest {

  private final ProjectRepoRepository repoRepo = mock(ProjectRepoRepository.class);
  private final ProjectRepoLinkRepository linkRepo = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepoService service =
      new ProjectRepoService(
          repoRepo,
          linkRepo,
          mock(ProjectRepository.class),
          mock(ProjectMemberRepository.class),
          mock(StackDetector.class),
          mock(GitHubRepoService.class),
          mock(AppUserRepository.class),
          mock(TierEnforcementService.class),
          mock(GovernanceGuard.class),
          mock(ObjectProvider.class));

  @Test
  void rejectsImplicitEmptySelectionWhenProjectHasLinkedRepositories() {
    UUID repoId = UUID.randomUUID();
    when(linkRepo.findByProjectId("project-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().repoId(repoId).build()));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveForRunStrict("project-1", List.of()));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void rejectsAnySelectedRepositoryThatIsNotLinkedAndResolvable() {
    UUID linkedId = UUID.randomUUID();
    UUID foreignId = UUID.randomUUID();
    ProjectRepo linked = ProjectRepo.builder().id(linkedId).build();
    when(linkRepo.findByProjectId("project-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().repoId(linkedId).build()));
    when(repoRepo.findById(linkedId)).thenReturn(Optional.of(linked));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveForRunStrict("project-1", List.of(linkedId, foreignId)));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void resolvesTheExactDistinctLinkedBoundary() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = ProjectRepo.builder().id(repoId).build();
    when(linkRepo.findByProjectId("project-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().repoId(repoId).build()));
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));

    var resolved = service.resolveForRunStrict("project-1", List.of(repoId, repoId));

    assertEquals(List.of(repo), resolved);
  }
}
