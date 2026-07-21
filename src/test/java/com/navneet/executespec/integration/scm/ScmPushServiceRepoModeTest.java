package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
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
import com.navneet.executespec.tool.GitToolResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * RR-900 — an omitted, unknown or misspelled repository mode must never resolve to "force-push the
 * remote default branch". Direct publication is reachable only for a repository this invocation
 * just created.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmPushServiceRepoModeTest {

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

  @Mock
  private com.navneet.executespec.state.repository.RunRepoSnapshotRepository
      runRepoSnapshotRepository;

  @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private ScmPushService service;

  private PipelineRun runWithMode(String repoMode) {
    // RR-1668 — no cloned-branch snapshot in these single-repo mode tests, so the push takes the
    // plain non-forced create path (pushToBranch is still invoked).
    lenient().when(runRepoSnapshotRepository.findByRunId(anyString())).thenReturn(List.of());
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_user_pat");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));
    return PipelineRun.builder()
        .runId("run-" + Math.abs(String.valueOf(repoMode).hashCode()))
        .createdByUserId("user-1")
        .scmProvider("GITHUB")
        .repoUrl("https://github.com/acme/customer-repo")
        .repoMode(repoMode)
        .userRequest("add a feature")
        .build();
  }

  @ParameterizedTest(name = "mode \"{0}\" must not force-push main")
  @ValueSource(strings = {"EXISTNG", "existing ", "exsiting", "PUSH", "new-repo", "  "})
  void unknownOrBlankModeNeverForcePushesTheDefaultBranch(String hostileMode) {
    PipelineRun run = runWithMode(hostileMode);
    when(gitTool.pushToBranch(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
        .thenReturn(GitToolResult.ok("pushed"));
    when(gitHubRepoService.getDefaultBranch(anyString(), anyString())).thenReturn("main");
    when(gitHubRepoService.createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(GitHubRepoService.PullRequestCreation.created("https://x/pull/7", 7));

    service.push(run);

    // The destructive path is never taken, whatever the mode string was.
    verify(gitTool, never()).pushToRemote(anyString(), anyString(), anyString());
  }

  @Test
  void aTypoInTheModeIsRefusedOutrightRatherThanReinterpreted() {
    PipelineRun run = runWithMode("EXISTNG");

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.message()).contains("Unsupported repository mode");
    verify(gitTool, never()).pushToRemote(anyString(), anyString(), anyString());
    verify(gitTool, never())
        .pushToBranch(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
  }

  @Test
  void absentModeFallsBackToTheSafeBranchAndPullRequestPath() {
    PipelineRun run = runWithMode(null);
    when(gitTool.pushToBranch(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
        .thenReturn(GitToolResult.ok("pushed"));
    when(gitHubRepoService.getDefaultBranch(anyString(), anyString())).thenReturn("main");
    when(gitHubRepoService.createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(GitHubRepoService.PullRequestCreation.created("https://x/pull/9", 9));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    verify(gitTool, never()).pushToRemote(anyString(), anyString(), anyString());
    verify(gitTool)
        .pushToBranch(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
  }

  /**
   * NEW mode pointed at a repository ExecuteSpec did NOT create in this invocation is a customer
   * repository; it gets a branch + PR, not an overwrite of its default branch.
   */
  @Test
  void newModeWithAPreexistingRepositoryUrlStillUsesBranchAndPullRequest() {
    PipelineRun run = runWithMode("NEW");
    when(gitTool.pushToBranch(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
        .thenReturn(GitToolResult.ok("pushed"));
    when(gitHubRepoService.getDefaultBranch(anyString(), anyString())).thenReturn("main");
    when(gitHubRepoService.createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(GitHubRepoService.PullRequestCreation.created("https://x/pull/3", 3));

    service.push(run);

    verify(gitTool, never()).pushToRemote(anyString(), anyString(), anyString());
    verify(gitHubRepoService, never()).createRepo(any(), any(), anyString(), anyString(), any());
  }

  @Test
  void directPublicationIsReachableOnlyForARepositoryCreatedByThisInvocation() {
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_user_pat");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-fresh")
            .createdByUserId("user-1")
            .scmProvider("GITHUB")
            .repoMode("NEW")
            .userRequest("greenfield app")
            .build();
    when(gitHubRepoService.createRepo(any(), any(), eq("private"), anyString(), any()))
        .thenReturn("https://github.com/acme/brand-new");
    when(stateStore.findRun("run-fresh")).thenReturn(Optional.of(run));
    when(gitTool.pushToRemote(anyString(), anyString(), anyString()))
        .thenReturn(GitToolResult.ok("pushed"));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    verify(gitTool).pushToRemote("run-fresh", "https://github.com/acme/brand-new", "ghp_user_pat");
  }

  @Test
  void noneModeIsNotAPublicationInstruction() {
    PipelineRun run = runWithMode("NONE");

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("NO_CONFIGURATION");
    verify(gitTool, never()).pushToRemote(anyString(), anyString(), anyString());
  }
}
