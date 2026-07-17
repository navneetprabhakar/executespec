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

  @Test
  void localSourceCannotFallThroughToTheCloudClonePipeline() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder().id(repoId).sourceType(ProjectRepo.SourceType.LOCAL.name()).build();
    when(linkRepo.findByProjectId("project-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().repoId(repoId).build()));
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveForRunStrict("project-1", List.of(repoId)));

    assertEquals(422, error.getStatusCode().value());
  }

  /* ── V278 local-run binding contract ── */

  @Test
  void localRunAdmitsOwnedOnlineLocalSourceForTheHoldingInstallation() {
    UUID repoId = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    ProjectRepo repo = onlineLocalSource(repoId, installation, "user-1");
    linkAndResolve(repoId, repo);
    when(repoRepo.save(repo)).thenReturn(repo);

    var resolved =
        service.resolveForLocalRunStrict("project-1", List.of(repoId), installation, "user-1");

    assertEquals(List.of(repo), resolved);
  }

  @Test
  void localRunWithoutInstallationIdentityIsRejectedWhenLocalSourceSelected() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = onlineLocalSource(repoId, UUID.randomUUID(), "user-1");
    linkAndResolve(repoId, repo);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveForLocalRunStrict("project-1", List.of(repoId), null, "user-1"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void localRunFromDifferentInstallationIsRejected() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = onlineLocalSource(repoId, UUID.randomUUID(), "user-1");
    linkAndResolve(repoId, repo);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.resolveForLocalRunStrict(
                    "project-1", List.of(repoId), UUID.randomUUID(), "user-1"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void localRunAgainstExpiredLeaseIsRejectedAsOffline() {
    UUID repoId = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    ProjectRepo repo = onlineLocalSource(repoId, installation, "user-1");
    repo.setSourceLeaseExpiresAt(java.time.Instant.now().minusSeconds(5));
    linkAndResolve(repoId, repo);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.resolveForLocalRunStrict(
                    "project-1", List.of(repoId), installation, "user-1"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void localRunByNonOwnerIsRejectedEvenWithMatchingInstallation() {
    UUID repoId = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    ProjectRepo repo = onlineLocalSource(repoId, installation, "owner-1");
    linkAndResolve(repoId, repo);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.resolveForLocalRunStrict(
                    "project-1", List.of(repoId), installation, "intruder-2"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void localRunPassesScmSourcesThroughWithoutInstallationIdentity() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = ProjectRepo.builder().id(repoId).build();
    linkAndResolve(repoId, repo);

    var resolved = service.resolveForLocalRunStrict("project-1", List.of(repoId), null, "user-1");

    assertEquals(List.of(repo), resolved);
  }

  private void linkAndResolve(UUID repoId, ProjectRepo repo) {
    when(linkRepo.findByProjectId("project-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().repoId(repoId).build()));
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));
  }

  private static ProjectRepo onlineLocalSource(UUID repoId, UUID installation, String owner) {
    return ProjectRepo.builder()
        .id(repoId)
        .scope(ProjectRepo.Scope.USER)
        .ownerUserId(owner)
        .sourceType(ProjectRepo.SourceType.LOCAL.name())
        .scmProvider(null)
        .localFolderId(UUID.randomUUID())
        .localInstallationId(installation)
        .sourceLeaseExpiresAt(java.time.Instant.now().plusSeconds(60))
        .build();
  }
}
