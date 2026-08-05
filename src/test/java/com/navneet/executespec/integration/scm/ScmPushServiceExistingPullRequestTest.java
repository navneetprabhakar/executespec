package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.sse.RunEvent;
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
    when(gitHubRepoService.findDefaultBranch(anyString(), anyString()))
        .thenReturn(GitHubRepoService.DefaultBranchLookup.found("main"));
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

  @Test
  void checkoutOnlyBranchStillCreatesAFreshGeneratedPullRequest() {
    PipelineRun run = existingRepoRun();
    run.setCheckoutBranch("feature/source");
    when(gitHubRepoService.createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(
            GitHubRepoService.PullRequestCreation.created(
                "https://github.com/acme/customer-repo/pull/21", 21));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.prUrl()).endsWith("/pull/21");
    verify(gitTool)
        .pushToBranch(
            eq(run.getRunId()),
            eq(run.getRepoUrl()),
            eq("ghp_user_pat"),
            eq("executespec/run-repu"),
            nullable(String.class));
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void explicitExistingBranchUpdatesItsPullRequestWithoutCreatingOrCleaningAnotherBranch() {
    PipelineRun run = existingRepoRun();
    run.setBranchOverride("feature/pr-12");
    run.setCheckoutBranch("feature/pr-12");
    run.setPublicationRepoFullName("acme/customer-repo");
    run.setPublicationPrNumber(12);
    when(gitTool.pushToExistingBranch(
            eq(run.getRunId()),
            eq("."),
            eq(run.getRepoUrl()),
            eq("ghp_user_pat"),
            eq("feature/pr-12"),
            anyString()))
        .thenReturn(GitToolResult.ok("feature/pr-12"));
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/customer-repo/pull/12", 12, "main", "a".repeat(40)));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.prUrl()).endsWith("/pull/12");
    assertThat(result.message()).contains("Existing").contains("updated");
    verify(broadcaster, atLeastOnce())
        .emit(
            eq(run.getRunId()),
            org.mockito.ArgumentMatchers.argThat(
                event ->
                    event instanceof RunEvent
                        && ((RunEvent) event).payload().contains("Existing pull request updated")));
    verify(gitTool, never())
        .pushToBranch(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
    verify(gitHubRepoService, never())
        .createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString());
    verify(sideEffectOutbox, never()).enqueueCompensation(anyString(), anyString(), any(Map.class));
  }

  @Test
  void cleanExistingBranchRetryReportsAlreadyCurrentInsteadOfUpdated() {
    PipelineRun run = existingRepoRun();
    run.setBranchOverride("feature/pr-12");
    run.setCheckoutBranch("feature/pr-12");
    run.setPublicationRepoFullName("acme/customer-repo");
    run.setPublicationPrNumber(12);
    when(gitTool.pushToExistingBranch(
            eq(run.getRunId()),
            eq("."),
            eq(run.getRepoUrl()),
            eq("ghp_user_pat"),
            eq("feature/pr-12"),
            anyString()))
        .thenReturn(GitToolResult.ok("ALREADY_CURRENT"));
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/customer-repo/pull/12", 12, "main", "a".repeat(40)));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.message()).contains("already current").contains("no new changes");
    verify(broadcaster, atLeastOnce())
        .emit(
            eq(run.getRunId()),
            org.mockito.ArgumentMatchers.argThat(
                event ->
                    event instanceof RunEvent
                        && ((RunEvent) event).payload().contains("already current")));
  }

  @Test
  void exactPublicationIdentityMismatchBlocksBeforeGitMutation() {
    PipelineRun run = existingRepoRun();
    run.setBranchOverride("feature/pr-12");
    run.setCheckoutBranch("feature/pr-12");
    run.setPublicationRepoFullName("acme/customer-repo");
    run.setPublicationPrNumber(16);
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/customer-repo/pull/12", 12, "main", "a".repeat(40)));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.code()).isEqualTo("SCM_PUBLICATION_IDENTITY_MISMATCH");
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void exactPublicationHeadAdvanceBlocksBeforeGitMutation() {
    PipelineRun run = existingRepoRun();
    run.setBranchOverride("feature/pr-12");
    run.setCheckoutBranch("feature/pr-12");
    run.setPublicationRepoFullName("acme/customer-repo");
    run.setPublicationPrNumber(12);
    run.setPublicationHeadSha("a".repeat(40));
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/customer-repo/pull/12", 12, "main", "b".repeat(40)));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.code()).isEqualTo("SCM_PUBLICATION_IDENTITY_MISMATCH");
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void explicitExistingBranchIsNeverOrphanCleanupEligibleWhenPrLookupFails() {
    PipelineRun run = existingRepoRun();
    run.setBranchOverride("feature/pr-12");
    when(gitTool.pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(GitToolResult.ok("feature/pr-12"));
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(GitHubRepoService.OpenPullRequestLookup.none());

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(sideEffectOutbox, never()).enqueueCompensation(anyString(), anyString(), any(Map.class));
  }

  @Test
  void multiRepoExplicitBranchUsesFastForwardUpdateAndReusesTheOpenPullRequest() throws Exception {
    UUID repoId = UUID.randomUUID();
    PipelineRun run = existingRepoRun();
    run.setProjectId("project-1");
    run.setSelectedRepoIds(new ObjectMapper().writeValueAsString(List.of(repoId)));
    run.setBranchOverride("feature/pr-12");
    run.setCheckoutBranch("feature/pr-12");
    run.setPublicationRepoFullName("acme/customer-repo");
    run.setPublicationPrNumber(12);
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/customer-repo")
            .defaultBranch("main")
            .build();
    when(projectRepoRepository.findById(repoId)).thenReturn(Optional.of(repo));
    when(projectRepoRepository.findAllById(List.of(repoId))).thenReturn(List.of(repo));
    when(gitTool.pushToExistingBranch(
            eq(run.getRunId()),
            eq("repos/customer-repo"),
            eq("https://github.com/acme/customer-repo"),
            eq("ghp_user_pat"),
            eq("feature/pr-12"),
            anyString()))
        .thenReturn(GitToolResult.ok("feature/pr-12"));
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/customer-repo/pull/12", 12, "main", "a".repeat(40)));
    when(pullRequestRecordService.recordCreated(
            eq(run.getRunId()),
            eq(repoId),
            eq("acme/customer-repo"),
            eq("https://github.com/acme/customer-repo/pull/12"),
            eq("feature/pr-12"),
            eq("main")))
        .thenReturn(PullRequestRecord.builder().runId(run.getRunId()).build());
    when(stateStore.findRun(run.getRunId())).thenReturn(Optional.of(run));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.prUrl()).endsWith("/pull/12");
    verify(gitTool, never())
        .commitAndPushSubdir(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class));
    verify(gitHubRepoService, never())
        .createPullRequestDetailed(
            anyString(), anyString(), anyString(), any(), any(), anyString());
    verify(sideEffectOutbox, never()).enqueueCompensation(anyString(), anyString(), any(Map.class));
  }

  @Test
  void selectedRepoCheckoutOnlyCreatesFreshGeneratedPullRequest() throws Exception {
    UUID repoId = UUID.randomUUID();
    PipelineRun run = existingRepoRun();
    run.setProjectId("project-1");
    run.setSelectedRepoIds(new ObjectMapper().writeValueAsString(List.of(repoId)));
    run.setCheckoutBranch("feature/source");
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/customer-repo")
            .defaultBranch("main")
            .build();
    when(projectRepoRepository.findById(repoId)).thenReturn(Optional.of(repo));
    when(projectRepoRepository.findAllById(List.of(repoId))).thenReturn(List.of(repo));
    when(gitTool.commitAndPushSubdir(
            eq(run.getRunId()),
            eq("repos/customer-repo"),
            eq("https://github.com/acme/customer-repo"),
            eq("ghp_user_pat"),
            eq("executespec/run-repu"),
            anyString(),
            nullable(String.class)))
        .thenReturn(GitToolResult.ok("executespec/run-repu"));
    when(gitHubRepoService.createPullRequestDetailed(
            eq("https://github.com/acme/customer-repo"),
            eq("executespec/run-repu"),
            eq("main"),
            any(),
            any(),
            eq("ghp_user_pat")))
        .thenReturn(
            GitHubRepoService.PullRequestCreation.created(
                "https://github.com/acme/customer-repo/pull/21", 21));
    when(pullRequestRecordService.recordCreated(
            eq(run.getRunId()),
            eq(repoId),
            eq("acme/customer-repo"),
            eq("https://github.com/acme/customer-repo/pull/21"),
            eq("executespec/run-repu"),
            eq("main")))
        .thenReturn(PullRequestRecord.builder().runId(run.getRunId()).build());
    when(stateStore.findRun(run.getRunId())).thenReturn(Optional.of(run));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.prUrl()).endsWith("/pull/21");
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void selectedRepoWrongExactPrIdentityIsTypedAndPreGit() throws Exception {
    UUID repoId = UUID.randomUUID();
    PipelineRun run = existingRepoRun();
    run.setProjectId("project-1");
    run.setSelectedRepoIds(new ObjectMapper().writeValueAsString(List.of(repoId)));
    run.setBranchOverride("feature/pr-12");
    run.setCheckoutBranch("feature/pr-12");
    run.setPublicationRepoFullName("acme/customer-repo");
    run.setPublicationPrNumber(16);
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/customer-repo")
            .defaultBranch("main")
            .build();
    when(projectRepoRepository.findById(repoId)).thenReturn(Optional.of(repo));
    when(projectRepoRepository.findAllById(List.of(repoId))).thenReturn(List.of(repo));
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/customer-repo/pull/12", 12, "main", "a".repeat(40)));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.code()).isEqualTo("SCM_PUBLICATION_IDENTITY_MISMATCH");
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(gitTool, never())
        .commitAndPushSubdir(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class));
  }

  @Test
  void selectedRepoPrLookupNegativesAreTypedAndPreGit() throws Exception {
    UUID repoId = UUID.randomUUID();
    PipelineRun run = existingRepoRun();
    run.setProjectId("project-1");
    run.setSelectedRepoIds(new ObjectMapper().writeValueAsString(List.of(repoId)));
    run.setBranchOverride("feature/pr-12");
    run.setCheckoutBranch("feature/pr-12");
    run.setPublicationRepoFullName("acme/customer-repo");
    run.setPublicationPrNumber(12);
    run.setPublicationHeadSha("a".repeat(40));
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/customer-repo")
            .defaultBranch("main")
            .build();
    when(projectRepoRepository.findById(repoId)).thenReturn(Optional.of(repo));
    when(projectRepoRepository.findAllById(List.of(repoId))).thenReturn(List.of(repo));
    when(gitHubRepoService.findOpenPullRequestForHead(
            "acme/customer-repo", "feature/pr-12", "ghp_user_pat"))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.unknown(),
            GitHubRepoService.OpenPullRequestLookup.none(),
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/customer-repo/pull/12", 12, "develop", "a".repeat(40)));

    assertThat(service.push(run).code()).isEqualTo("SCM_EXISTING_PR_LOOKUP_UNAVAILABLE");
    assertThat(service.push(run).code()).isEqualTo("SCM_EXISTING_PR_REQUIRED");
    assertThat(service.push(run).code()).isEqualTo("SCM_EXISTING_PR_BASE_MISMATCH");
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void multiRepoDefaultBranchOverrideIsRejectedBeforeAnyGitMutation() throws Exception {
    UUID repoId = UUID.randomUUID();
    PipelineRun run = existingRepoRun();
    run.setProjectId("project-1");
    run.setSelectedRepoIds(new ObjectMapper().writeValueAsString(List.of(repoId)));
    run.setBranchOverride("main");
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/customer-repo")
            .defaultBranch("main")
            .build();
    when(projectRepoRepository.findById(repoId)).thenReturn(Optional.of(repo));
    when(projectRepoRepository.findAllById(List.of(repoId))).thenReturn(List.of(repo));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("FAILED");
    verify(gitTool, never())
        .pushToExistingBranch(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(gitTool, never())
        .commitAndPushSubdir(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class));
  }
}
