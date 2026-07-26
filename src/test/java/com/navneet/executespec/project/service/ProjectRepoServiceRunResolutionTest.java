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
import com.navneet.executespec.repo.GitHubScmRepositoryClient;
import com.navneet.executespec.repo.ScmCapability;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

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
          new ScmRepositoryClientRegistry(
              List.of(new GitHubScmRepositoryClient(mock(GitHubRepoService.class)))),
          mock(ScmCredentialResolver.class),
          mock(AppUserRepository.class),
          mock(TierEnforcementService.class),
          mock(GovernanceGuard.class),
          mock(ProjectSourceFreshnessInvalidator.class),
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

  @Test
  void resolveForRunStrictRejectsBlockedSourceReadiness() { // RR-313
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .sourceReadinessStatus(ProjectRepo.SourceReadiness.BLOCKED.name())
            .sourceReadinessReason("EMPTY_REPOSITORY")
            .build();
    linkAndResolve(repoId, repo);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveForRunStrict("project-1", List.of(repoId)));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void resolveForRunStrictAdmitsReadySource() { // RR-313
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .sourceReadinessStatus(ProjectRepo.SourceReadiness.READY.name())
            .build();
    linkAndResolve(repoId, repo);

    assertEquals(List.of(repo), service.resolveForRunStrict("project-1", List.of(repoId)));
  }

  @Test
  void requireProjectCapabilityRejectsProjectWithNoRepositories() { // RR-315
    when(linkRepo.findByProjectId("project-1")).thenReturn(List.of());

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.requireProjectCapability(
                    "project-1", ScmCapability.CLOUD_RUN, "regenerate architecture"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void requireProjectCapabilityRejectsLocalOnlyProject() { // RR-315
    UUID repoId = UUID.randomUUID();
    ProjectRepo local =
        ProjectRepo.builder().id(repoId).sourceType(ProjectRepo.SourceType.LOCAL.name()).build();
    linkAndResolve(repoId, local);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.requireProjectCapability(
                    "project-1", ScmCapability.CLOUD_RUN, "regenerate architecture"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void requireProjectCapabilityRejectsDanglingLink() { // RR-315
    UUID repoId = UUID.randomUUID();
    when(linkRepo.findByProjectId("project-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().repoId(repoId).build()));
    when(repoRepo.findById(repoId)).thenReturn(Optional.empty());

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.requireProjectCapability(
                    "project-1", ScmCapability.CLOUD_RUN, "regenerate architecture"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void requireProjectCapabilityPassesForCapableScmRepository() { // RR-315
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = ProjectRepo.builder().id(repoId).build();
    linkAndResolve(repoId, repo);

    service.requireProjectCapability(
        "project-1", ScmCapability.CLOUD_RUN, "regenerate architecture");
  }

  @Test
  void reviewResolutionRequiresRepoId() {
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveGitHubReviewRepoStrict("project-1", null, "org/repo"));

    assertEquals(400, error.getStatusCode().value());
  }

  @Test
  void reviewResolutionRejectsRepositoryNotLinkedToProject() {
    UUID foreignRepoId = UUID.randomUUID();
    when(linkRepo.findByProjectId("project-1")).thenReturn(List.of());

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveGitHubReviewRepoStrict("project-1", foreignRepoId, "other/repo"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void reviewResolutionRejectsClaimedNameThatDoesNotMatchLinkedIdentity() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider("GITHUB")
            .githubRepoFullName("canonical/repo")
            .build();
    linkAndResolve(repoId, repo);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.resolveGitHubReviewRepoStrict(
                    "project-1", repoId, "attacker/arbitrary-repo"));

    assertEquals(400, error.getStatusCode().value());
  }

  @Test
  void reviewResolutionRejectsNonGitHubScm() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider("BITBUCKET")
            .sourceKey("workspace/repo")
            .githubRepoFullName("workspace/repo")
            .build();
    linkAndResolve(repoId, repo);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveGitHubReviewRepoStrict("project-1", repoId, "workspace/repo"));

    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void reviewResolutionReturnsCanonicalLinkedGitHubRepository() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider("GITHUB")
            .githubRepoFullName("Canonical/Repo")
            .build();
    linkAndResolve(repoId, repo);

    ProjectRepo resolved =
        service.resolveGitHubReviewRepoStrict("project-1", repoId, "canonical/repo");

    assertEquals(repo, resolved);
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
