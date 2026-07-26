package com.navneet.executespec.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmProvider;
import com.navneet.executespec.repo.ScmRemoteRepository;
import com.navneet.executespec.repo.ScmRepositoryClient;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * RR-318 — a repository created + linked in one shot must carry the same neutral SCM identity +
 * readiness contract as an attached one, not just legacy name/owner columns.
 */
class ProjectRepoServiceCreatedRepoReadinessTest {

  private final ProjectRepoRepository repoRepo = mock(ProjectRepoRepository.class);
  private final ProjectRepoLinkRepository linkRepo = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final GitHubRepoService gitHub = mock(GitHubRepoService.class);
  private final ScmRepositoryClient github = mock(ScmRepositoryClient.class);
  private ProjectRepoService service;

  @BeforeEach
  void setUp() {
    when(github.provider()).thenReturn(ScmProvider.GITHUB);
    service =
        new ProjectRepoService(
            repoRepo,
            linkRepo,
            projectRepo,
            mock(ProjectMemberRepository.class),
            mock(StackDetector.class),
            gitHub,
            new ScmRepositoryClientRegistry(List.of(github)),
            mock(com.navneet.executespec.repo.ScmCredentialResolver.class),
            mock(AppUserRepository.class),
            mock(TierEnforcementService.class),
            mock(GovernanceGuard.class),
            mock(ProjectSourceFreshnessInvalidator.class),
            mock(ObjectProvider.class));
  }

  @Test
  void createdRepoIsHydratedWithProviderIdentityAndHonestReadiness() { // RR-318
    when(projectRepo.findById("proj-1"))
        .thenReturn(Optional.of(Project.builder().orgId("org-1").name("Proj").build()));
    when(gitHub.createRepo(any(), any(), any(), any(), any()))
        .thenReturn("https://github.com/acme/new-svc.git");
    when(gitHub.installWebhook(anyString(), any())).thenReturn(false);
    when(github.inspect(anyString(), any()))
        .thenReturn(
            new ScmRemoteRepository(
                "GITHUB",
                "gh-immutable-42",
                "acme/new-svc",
                "acme/new-svc",
                "https://github.com/acme/new-svc.git",
                "main",
                true,
                0L,
                Set.of("CLOUD_RUN", "SYNC")));
    // A brand-new repository has no commits — the branch head is unreadable.
    when(github.branchHeadSha(any(), anyString(), any()))
        .thenThrow(new IllegalStateException("no commits"));
    when(github.inventory(any(), anyString(), any()))
        .thenThrow(new IllegalStateException("empty repo"));
    when(repoRepo.save(any(ProjectRepo.class))).thenAnswer(inv -> inv.getArgument(0));

    service.createAndLinkNewRepo(
        "proj-1", "new-svc", "desc", "acme", "private", "pat-xyz", "user-1", "org-1");

    ArgumentCaptor<ProjectRepo> saved = ArgumentCaptor.forClass(ProjectRepo.class);
    org.mockito.Mockito.verify(repoRepo).save(saved.capture());
    ProjectRepo entity = saved.getValue();
    assertThat(entity.getSourceType()).isEqualTo(ProjectRepo.SourceType.SCM.name());
    assertThat(entity.getScmProvider()).isEqualTo(ScmProvider.GITHUB.name());
    assertThat(entity.getScmRepositoryId()).isEqualTo("gh-immutable-42");
    assertThat(entity.getScmCloneUrl()).isEqualTo("https://github.com/acme/new-svc.git");
    // No proven readable revision → BLOCKED, so RR-313 run admission refuses it until code is
    // pushed.
    assertThat(entity.getSourceReadinessStatus())
        .isEqualTo(ProjectRepo.SourceReadiness.BLOCKED.name());
  }
}
