package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * RR-146 — republishing a run whose pull request already exists must not schedule deletion of that
 * pull request's head branch. Only a definitive creation FAILURE may enqueue cleanup.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmPushServiceExistingPullRequestTest {

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

  @Mock
  private com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard
      brownfieldHttpContractGuard;

  @Mock private PublicationAdmissionService publicationAdmissionService;
  @Mock private SideEffectOutbox sideEffectOutbox;

  @Mock
  private com.navneet.executespec.state.repository.RunRepoSnapshotRepository
      runRepoSnapshotRepository;

  @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private ScmPushService service;

  @org.junit.jupiter.api.BeforeEach
  void admitPublicationByDefault() {
    org.mockito.Mockito.lenient()
        .when(publicationAdmissionService.assertEligible(any(PipelineRun.class)))
        .thenAnswer(
            ignored ->
                new PublicationAdmissionService.Admission("a".repeat(64), "PUBLICATION_ELIGIBLE"));
  }

  private PipelineRun existingRepoRun() {
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_user_pat");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));
    // RR-1668 — single-repo run with no cloned-branch snapshot ⇒ plain non-forced push path.
    when(runRepoSnapshotRepository.findByRunId(anyString())).thenReturn(List.of());
    when(gitTool.pushToBranch(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
        .thenReturn(GitToolResult.ok("pushed"));
    when(gitHubRepoService.getDefaultBranch(anyString(), anyString())).thenReturn("main");
    return PipelineRun.builder()
        .runId("run-republish")
        .createdByUserId("user-1")
        .scmProvider("GITHUB")
        .repoUrl("https://github.com/acme/customer-repo")
        .repoMode("EXISTING")
        .userRequest("re-publish")
        .build();
  }

  @Test
  void anAlreadyExistingPullRequestIsSuccessAndNeverEnqueuesBranchDeletion() {
    PipelineRun run = existingRepoRun();
    when(gitHubRepoService.createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(
            GitHubRepoService.PullRequestCreation.alreadyExists(
                "https://github.com/acme/customer-repo/pull/12", 12));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.prUrl()).endsWith("/pull/12");
    verify(sideEffectOutbox, never()).enqueueCompensation(anyString(), anyString(), any(Map.class));
  }

  /** Even when the existing PR's URL cannot be discovered, deletion stays forbidden. */
  @Test
  void anAlreadyExistingPullRequestWithUnknownUrlStillBlocksCleanup() {
    PipelineRun run = existingRepoRun();
    when(gitHubRepoService.createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(GitHubRepoService.PullRequestCreation.alreadyExists(null, null));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    verify(sideEffectOutbox, never()).enqueueCompensation(anyString(), anyString(), any(Map.class));
  }

  @Test
  void aDefinitiveCreationFailureStillEnqueuesTheOrphanBranchCompensation() {
    PipelineRun run = existingRepoRun();
    when(gitHubRepoService.createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(GitHubRepoService.PullRequestCreation.failed("GitHub returned status 500"));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    verify(sideEffectOutbox)
        .enqueueCompensation(
            anyString(),
            org.mockito.ArgumentMatchers.eq("COMPENSATE_GITHUB_BRANCH"),
            any(Map.class));
  }
}
