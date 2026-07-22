package com.navneet.executespec.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RR-319 — the workspace-health report must resolve latest-per-repo codemaps in SQL rather than
 * materializing the project's entire codemap history in memory.
 */
class WorkspaceHealthServiceLatestCodeMapTest {

  private final ProjectRepoLinkRepository linkRepo = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepoRepository repoRepo = mock(ProjectRepoRepository.class);
  private final ProjectCodeMapRepository codeMapRepo = mock(ProjectCodeMapRepository.class);
  private final PullRequestRecordRepository prRepo = mock(PullRequestRecordRepository.class);

  private final WorkspaceHealthService service =
      new WorkspaceHealthService(
          linkRepo,
          repoRepo,
          codeMapRepo,
          prRepo,
          mock(ScmRepositoryClientRegistry.class),
          mock(ScmCredentialResolver.class),
          mock(StringRedisTemplate.class));

  @Test
  void reportUsesLatestPerRepoProjectionNotFullHistory() { // RR-319
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/widget")
            .sourceType("LOCAL") // avoids any SCM/Redis path — callerUserId null returns UNKNOWN
            .defaultBranch("main")
            .build();
    when(linkRepo.findByProjectId("proj-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().projectId("proj-1").repoId(repoId).build()));
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(codeMapRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc("proj-1", repoId))
        .thenReturn(
            Optional.of(
                ProjectCodeMap.builder()
                    .projectId("proj-1")
                    .repoId(repoId)
                    .version(9)
                    .generatedFromSha("sha-9")
                    .build()));

    WorkspaceHealthService.Report report = service.report("proj-1", null);

    assertThat(report.repos()).hasSize(1);
    assertThat(report.repos().get(0).lastCodeMapSha()).isEqualTo("sha-9");
    verify(codeMapRepo).findFirstByProjectIdAndRepoIdOrderByVersionDesc("proj-1", repoId);
    // The unbounded whole-history load must no longer be used.
    verify(codeMapRepo, never()).findByProjectId(any());
    verify(codeMapRepo, never()).findByProjectId(eq("proj-1"));
  }
}
