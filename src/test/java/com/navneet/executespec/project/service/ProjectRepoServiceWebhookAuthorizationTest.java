package com.navneet.executespec.project.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class ProjectRepoServiceWebhookAuthorizationTest {

  @Test
  void organizationMemberCannotInstallWebhookOnSharedRepoWithoutAdminAuthority() {
    UUID repoId = UUID.randomUUID();
    ProjectRepoRepository repoRepository = mock(ProjectRepoRepository.class);
    GitHubRepoService gitHub = mock(GitHubRepoService.class);
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .scope(ProjectRepo.Scope.ORG)
            .ownerOrgId("org-1")
            .githubRepoFullName("acme/api")
            .sourceCapabilities(List.of("WEBHOOK"))
            .build();
    when(repoRepository.findById(repoId)).thenReturn(Optional.of(repo));
    ProjectRepoService service =
        new ProjectRepoService(
            repoRepository,
            mock(ProjectRepoLinkRepository.class),
            mock(ProjectRepository.class),
            mock(ProjectMemberRepository.class),
            mock(StackDetector.class),
            gitHub,
            new ScmRepositoryClientRegistry(List.of(new GitHubScmRepositoryClient(gitHub))),
            mock(ScmCredentialResolver.class),
            mock(AppUserRepository.class),
            mock(TierEnforcementService.class),
            mock(GovernanceGuard.class),
            mock(ProjectSourceFreshnessInvalidator.class),
            mock(ObjectProvider.class));

    assertThatThrownBy(() -> service.installWebhook(repoId, "pat", "user-1", "org-1", false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Only ORG_ADMIN");

    verify(gitHub, never()).installWebhook(any(), any());
    verify(repoRepository, never()).save(any());
  }
}
