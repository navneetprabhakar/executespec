package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.GitTool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScmPushServiceProviderRoutingTest {

  @Mock private GitTool gitTool;
  @Mock private GitHubRepoService gitHubRepoService;
  @Mock private BitbucketRepoService bitbucketRepoService;
  @Mock private AgentProperties props;
  @Mock private StateStore stateStore;
  @Mock private SseBroadcaster broadcaster;
  @Mock private AppUserRepository appUserRepository;
  @Mock private ProjectRepository projectRepository;
  @Mock private PullRequestRecordService pullRequestRecordService;
  @Mock private PullRequestRecordRepository pullRequestRecordRepository;
  @Mock private ProjectRepoRepository projectRepoRepository;
  @Mock private ArtifactPublicationGuard publicationGuard;
  @Mock private SideEffectOutbox sideEffectOutbox;

  @InjectMocks private ScmPushService service;

  @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void explicitGitHubRouteWinsWhenUserAlsoHasBitbucketCredentials() {
    AppUser user = dualCredentialUser();
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-explicit-github")
            .createdByUserId("user-1")
            .scmProvider("GITHUB")
            .repoMode("EXISTING")
            .build();
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("NO_CONFIGURATION");
    assertThat(result.scmProvider()).isEqualTo("GITHUB");
    verifyNoInteractions(bitbucketRepoService);
  }

  @Test
  void legacyDualCredentialRunWithoutRouteFailsClosed() {
    AppUser user = dualCredentialUser();
    PipelineRun run =
        PipelineRun.builder().runId("run-ambiguous").createdByUserId("user-1").build();
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.message()).contains("multiple SCM credentials");
    verifyNoInteractions(bitbucketRepoService, gitHubRepoService, gitTool);
  }

  @Test
  void selectedBitbucketProjectSourceIsRejectedInsteadOfPushedThroughLegacyRootPath()
      throws Exception {
    UUID repoId = UUID.randomUUID();
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-project-bitbucket")
            .selectedRepoIds(new ObjectMapper().writeValueAsString(List.of(repoId)))
            .build();
    ProjectRepo repo = ProjectRepo.builder().id(repoId).scmProvider("BITBUCKET").build();
    when(projectRepoRepository.findAllById(List.of(repoId))).thenReturn(List.of(repo));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.scmProvider()).isEqualTo("BITBUCKET");
    assertThat(result.message()).contains("not supported on this release");
    verifyNoInteractions(bitbucketRepoService, gitHubRepoService, gitTool);
  }

  private static AppUser dualCredentialUser() {
    AppUser user = new AppUser();
    user.setGithubPat("github-secret");
    user.setBitbucketUsername("forge");
    user.setBitbucketWorkspace("workspace");
    user.setBitbucketRepoSlug("repo");
    user.setBitbucketAppPassword("bitbucket-secret");
    return user;
  }
}
