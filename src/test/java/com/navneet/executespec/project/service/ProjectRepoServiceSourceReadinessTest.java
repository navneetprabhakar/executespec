package com.navneet.executespec.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.GitHubScmRepositoryClient;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ProjectRepoServiceSourceReadinessTest {

  private final ProjectRepoRepository repoRepo = mock(ProjectRepoRepository.class);
  private final StackDetector stackDetector = mock(StackDetector.class);
  private final GitHubRepoService gitHub = mock(GitHubRepoService.class);
  private final ProjectSourceFreshnessInvalidator freshnessInvalidator =
      mock(ProjectSourceFreshnessInvalidator.class);
  private final ProjectRepoService service =
      new ProjectRepoService(
          repoRepo,
          mock(ProjectRepoLinkRepository.class),
          mock(ProjectRepository.class),
          mock(ProjectMemberRepository.class),
          stackDetector,
          gitHub,
          new ScmRepositoryClientRegistry(List.of(new GitHubScmRepositoryClient(gitHub))),
          mock(ScmCredentialResolver.class),
          mock(AppUserRepository.class),
          mock(TierEnforcementService.class),
          mock(GovernanceGuard.class),
          freshnessInvalidator,
          mock(ObjectProvider.class));

  private final UUID repoId = UUID.randomUUID();
  private ProjectRepo repo;

  @BeforeEach
  void setUp() {
    repo =
        ProjectRepo.builder()
            .id(repoId)
            .scope(ProjectRepo.Scope.USER)
            .ownerUserId("user-1")
            .githubRepoFullName("acme/api")
            .githubOwner("acme")
            .defaultBranch("main")
            .build();
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(repoRepo.save(any(ProjectRepo.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(gitHub.getRemoteRepoResult("acme/api", "pat"))
        .thenReturn(
            GitHubRepoService.RemoteRepoLookupResult.found(
                new GitHubRepoService.RemoteRepo(
                    "123", "acme/api", "https://github.com/acme/api", "main", true, 10L)));
  }

  @Test
  void syncMarksSourceReadyOnlyAfterBranchAndInventoryAreProven() {
    when(gitHub.getBranchHeadSha("https://github.com/acme/api", "main", "pat"))
        .thenReturn("0123456789012345678901234567890123456789");
    when(gitHub.listRepoFilesResult("https://github.com/acme/api", "main", "pat"))
        .thenReturn(
            GitHubRepoService.RepoFileListResult.success(
                List.of("src/App.java", "src/AppTest.java")));

    var result = service.syncRepo(repoId, "user-1", null, false, "pat");

    assertThat(result.sourceReadinessStatus()).isEqualTo("READY");
    assertThat(result.sourceReadinessReason()).isEqualTo("SOURCE_INVENTORY_CONFIRMED");
    assertThat(result.sourceFileCount()).isEqualTo(2);
    assertThat(result.sourceReadinessCheckedAt()).isNotNull();
    verify(freshnessInvalidator).invalidateRepo(repoId);
  }

  @Test
  void syncPreservesUpstreamFailureAsBlockedInsteadOfEmptyReadyRepo() {
    when(gitHub.getBranchHeadSha("https://github.com/acme/api", "main", "pat")).thenReturn(null);
    when(gitHub.listRepoFilesResult("https://github.com/acme/api", "main", "pat"))
        .thenReturn(GitHubRepoService.RepoFileListResult.failed("GITHUB_TREE_HTTP_ERROR", 403));

    var result = service.syncRepo(repoId, "user-1", null, false, "pat");

    assertThat(result.sourceReadinessStatus()).isEqualTo("BLOCKED");
    assertThat(result.sourceReadinessReason()).isEqualTo("GITHUB_TREE_HTTP_ERROR");
    assertThat(result.sourceFileCount()).isNull();
  }

  @Test
  void syncDoesNotClaimFullReadinessForATruncatedLargeRepositoryTree() {
    when(gitHub.getBranchHeadSha("https://github.com/acme/api", "main", "pat"))
        .thenReturn("0123456789012345678901234567890123456789");
    when(gitHub.listRepoFilesResult("https://github.com/acme/api", "main", "pat"))
        .thenReturn(
            GitHubRepoService.RepoFileListResult.success(
                List.of("src/App.java", "src/AppTest.java"), true));

    var result = service.syncRepo(repoId, "user-1", null, false, "pat");

    assertThat(result.sourceReadinessStatus()).isEqualTo("INVENTORIED");
    assertThat(result.sourceReadinessReason()).isEqualTo("SOURCE_INVENTORY_TRUNCATED");
    assertThat(result.sourceFileCount()).isEqualTo(2);
  }

  @Test
  void localSourceCannotEnterGithubMetadataSync() {
    repo.setSourceType(ProjectRepo.SourceType.LOCAL.name());
    repo.setGithubRepoFullName(null);
    repo.setGithubOwner(null);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.syncRepo(repoId, "user-1", null, false, "pat"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("LOCAL sources cannot sync");
    org.mockito.Mockito.verifyNoInteractions(stackDetector, gitHub);
  }

  @Test
  void organizationMemberCannotSyncSharedRepoWithoutAdminAuthority() {
    repo.setScope(ProjectRepo.Scope.ORG);
    repo.setOwnerUserId(null);
    repo.setOwnerOrgId("org-1");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.syncRepo(repoId, "user-1", "org-1", false, "pat"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("Only ORG_ADMIN");

    verify(gitHub, never()).getRemoteRepoResult(any(), any());
    verify(repoRepo, never()).save(any());
  }
}
