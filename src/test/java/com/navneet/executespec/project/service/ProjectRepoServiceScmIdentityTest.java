package com.navneet.executespec.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.project.dto.CreateRepoRequest;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmCapability;
import com.navneet.executespec.repo.ScmCredential;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmProvider;
import com.navneet.executespec.repo.ScmRemoteRepository;
import com.navneet.executespec.repo.ScmRepositoryClient;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;
import com.navneet.executespec.repo.ScmSourceInventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class ProjectRepoServiceScmIdentityTest {
  private final ProjectRepoRepository repositories = mock(ProjectRepoRepository.class);
  private final ScmRepositoryClient bitbucket = mock(ScmRepositoryClient.class);
  private final ScmCredentialResolver credentials = mock(ScmCredentialResolver.class);
  private final StackDetector stackDetector = mock(StackDetector.class);
  private ProjectRepoService service;

  @BeforeEach
  void setUp() {
    when(bitbucket.provider()).thenReturn(ScmProvider.BITBUCKET);
    when(bitbucket.capabilities())
        .thenReturn(
            Set.of(
                ScmCapability.CATALOG,
                ScmCapability.ATTACH,
                ScmCapability.SYNC,
                ScmCapability.SOURCE_READINESS,
                ScmCapability.SOURCE_BROWSE));
    when(bitbucket.supports(any())).thenCallRealMethod();
    when(credentials.require("user-1", ScmProvider.BITBUCKET))
        .thenReturn(new ScmCredential("forge", "secret"));
    service =
        new ProjectRepoService(
            repositories,
            mock(ProjectRepoLinkRepository.class),
            mock(ProjectRepository.class),
            mock(ProjectMemberRepository.class),
            stackDetector,
            mock(GitHubRepoService.class),
            new ScmRepositoryClientRegistry(List.of(bitbucket)),
            credentials,
            mock(AppUserRepository.class),
            mock(TierEnforcementService.class),
            mock(GovernanceGuard.class),
            mock(ProjectSourceFreshnessInvalidator.class),
            mock(ObjectProvider.class));
  }

  @Test
  void newAttachPersistsProviderImmutableIdentityAndNoCredential() {
    ScmRemoteRepository remote = remote("{immutable-1}", "acme/renamed-api");
    when(bitbucket.inspect("acme/api", new ScmCredential("forge", "secret"))).thenReturn(remote);
    when(bitbucket.branchHeadSha(remote, "main", new ScmCredential("forge", "secret")))
        .thenReturn("abc123");
    when(bitbucket.inventory(remote, "main", new ScmCredential("forge", "secret")))
        .thenReturn(ScmSourceInventory.success(List.of("README.md"), false));
    when(repositories.findByOwnerUserIdAndScmProviderAndScmRepositoryId(
            "user-1", "BITBUCKET", "{immutable-1}"))
        .thenReturn(Optional.empty());

    service.createRepo(
        new CreateRepoRequest("USER", "BITBUCKET", "acme/api", null, "{immutable-1}", null, false),
        "user-1",
        "org-1",
        false);

    ArgumentCaptor<ProjectRepo> saved = ArgumentCaptor.forClass(ProjectRepo.class);
    verify(repositories).save(saved.capture());
    assertThat(saved.getValue().getScmProvider()).isEqualTo("BITBUCKET");
    assertThat(saved.getValue().getScmRepositoryId()).isEqualTo("{immutable-1}");
    assertThat(saved.getValue().getSourceKey()).isEqualTo("acme/renamed-api");
    assertThat(saved.getValue().getGithubRepoFullName()).isNull();
    assertThat(saved.getValue().getScmCloneUrl())
        .isEqualTo("https://bitbucket.org/acme/renamed-api.git")
        .doesNotContain("forge", "secret", "@");
    assertThat(saved.getValue().getSourceCapabilities())
        .doesNotContain(
            "ARCHITECTURE", "CODE_REVIEW", "WEBHOOK", "BOOTSTRAP", "CLOUD_RUN", "LOCAL_RUN");
  }

  @Test
  void rejectsStaleCatalogueIdentityBeforePersistence() {
    when(bitbucket.inspect("acme/api", new ScmCredential("forge", "secret")))
        .thenReturn(remote("{actual}", "acme/api"));

    assertThatThrownBy(
            () ->
                service.createRepo(
                    new CreateRepoRequest(
                        "USER", "BITBUCKET", "acme/api", null, "{stale}", null, false),
                    "user-1",
                    "org-1",
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");
    verify(repositories, never()).save(any());
  }

  @Test
  void serverCapabilityGateRejectsGitHubOnlyActionsForBitbucket() {
    ProjectRepo repo =
        ProjectRepo.builder()
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider("BITBUCKET")
            .sourceKey("acme/api")
            .build();

    assertThatThrownBy(
            () ->
                service.requireCapability(
                    repo, ScmCapability.ARCHITECTURE, "generate architecture"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("422 UNPROCESSABLE_ENTITY")
        .hasMessageContaining("BITBUCKET");
  }

  private static ScmRemoteRepository remote(String id, String fullName) {
    return new ScmRemoteRepository(
        "BITBUCKET",
        id,
        fullName,
        fullName,
        "https://bitbucket.org/" + fullName + ".git",
        "main",
        true,
        2L,
        Set.of("CATALOG", "ATTACH", "SYNC", "SOURCE_READINESS", "SOURCE_BROWSE"));
  }
}
