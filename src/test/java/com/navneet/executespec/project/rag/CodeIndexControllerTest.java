package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CodeIndexControllerTest {

  private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
  private final ProjectRepoLinkRepository links = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepoRepository repos = mock(ProjectRepoRepository.class);
  private final CodeIndexingService indexing = mock(CodeIndexingService.class);
  private final CodeIndexController controller =
      new CodeIndexController(guard, links, repos, indexing);
  private final AppUserDetails principal = principal();

  @Test
  void listIsTenantGuardedAndOnlyReturnsLinkedRepositories() {
    UUID linkedId = UUID.randomUUID();
    UUID unrelatedId = UUID.randomUUID();
    ProjectRepo linked = repo(linkedId, "acme/linked");
    when(links.findByProjectId("project-1"))
        .thenReturn(
            List.of(
                ProjectRepoLink.builder()
                    .projectId("project-1")
                    .repoId(linkedId)
                    .linkedBy("user-1")
                    .build()));
    when(repos.findById(linkedId)).thenReturn(Optional.of(linked));
    when(repos.findById(unrelatedId)).thenReturn(Optional.of(repo(unrelatedId, "other/secret")));
    when(indexing.readiness("project-1", linked)).thenReturn(readiness(linkedId));

    var result = controller.list("project-1", principal);

    assertThat(result)
        .singleElement()
        .extracting(CodeIndexController.RepoIndexStatus::repoName)
        .isEqualTo("acme/linked");
    verify(guard).assertCanRead("project-1", principal);
    verify(repos, never()).findById(unrelatedId);
  }

  @Test
  void deniedTenantNeverReachesRepoOrIndexStores() {
    doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
        .when(guard)
        .assertCanRead("foreign-project", principal);

    assertThatThrownBy(() -> controller.list("foreign-project", principal))
        .isInstanceOf(ResponseStatusException.class);

    verify(links, never()).findByProjectId("foreign-project");
    verify(indexing, never())
        .readiness(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void refreshRequiresWriteAndUsesBitbucketUsernameWithoutPersistingItInResponse() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = repo(repoId, "workspace/service");
    repo.setScmProvider("BITBUCKET");
    when(links.findByProjectIdAndRepoId("project-1", repoId))
        .thenReturn(
            Optional.of(ProjectRepoLink.builder().repoId(repoId).projectId("project-1").build()));
    when(repos.findById(repoId)).thenReturn(Optional.of(repo));
    when(indexing.readiness("project-1", repo)).thenReturn(partialReadiness(repoId));

    var response = controller.refresh("project-1", repoId, principal);

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getBody().fallback()).isEqualTo("RIPGREP");
    assertThat(response.getBody().relationshipGraphAvailable()).isFalse();
    verify(guard).assertCanWrite("project-1", principal);
    verify(indexing)
        .indexRepoExplicitAsync("project-1", repo, "bb-user", "bb-app-password", "user-1");
  }

  @Test
  void refreshOfReadyIndexIsIdempotentAndDoesNotQueueProviderWork() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo = repo(repoId, "acme/ready");
    when(links.findByProjectIdAndRepoId("project-1", repoId))
        .thenReturn(
            Optional.of(ProjectRepoLink.builder().repoId(repoId).projectId("project-1").build()));
    when(repos.findById(repoId)).thenReturn(Optional.of(repo));
    when(indexing.readiness("project-1", repo)).thenReturn(readiness(repoId));

    var response = controller.refresh("project-1", repoId, principal);

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getBody().status()).isEqualTo("READY");
    verify(indexing, never())
        .indexRepoExplicitAsync(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  private static AppUserDetails principal() {
    return new AppUserDetails(
        AppUser.builder()
            .id("user-1")
            .orgId("org-1")
            .email("user@example.com")
            .globalRole("USER")
            .status("ACTIVE")
            .tier("TEAM")
            .githubPat("gh-secret")
            .bitbucketUsername("bb-user")
            .bitbucketAppPassword("bb-app-password")
            .build(),
        null);
  }

  private static ProjectRepo repo(UUID id, String name) {
    return ProjectRepo.builder()
        .id(id)
        .githubRepoFullName(name)
        .sourceDisplayName(name)
        .lastSyncedSha("a".repeat(40))
        .build();
  }

  private static CodeIndexingService.Readiness readiness(UUID repoId) {
    return new CodeIndexingService.Readiness(
        "project-1",
        repoId.toString(),
        "a".repeat(40),
        "a".repeat(40),
        CodeIndexStateStore.Status.READY,
        "FULL_SOURCE_COVERAGE",
        1,
        1,
        1,
        1,
        0,
        0,
        0,
        0,
        1,
        100,
        false,
        true,
        true,
        false,
        "SEMANTIC",
        "openai",
        EmbeddingService.DEFAULT_MODEL);
  }

  private static CodeIndexingService.Readiness partialReadiness(UUID repoId) {
    var ready = readiness(repoId);
    return new CodeIndexingService.Readiness(
        ready.projectId(),
        ready.repoId(),
        ready.currentSourceSha(),
        ready.indexedSourceSha(),
        CodeIndexStateStore.Status.PARTIAL,
        "REFRESH_LIMIT_REACHED_RESUMABLE",
        2,
        2,
        1,
        1,
        0,
        0,
        0,
        0,
        1,
        50,
        false,
        false,
        true,
        false,
        "LEXICAL",
        null,
        null);
  }
}
