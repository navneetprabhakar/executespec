package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.navneet.executespec.project.pr.PullRequestRecord;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 2026-08-05 (dev run b4a5f5bc) — multi-repo PR publication must be fault-isolated per repository.
 * Previously a {@code PullRequestRecordService.recordCreated} exception for the second repository
 * escaped the per-repo loop AFTER both PRs already existed on GitHub: the second PR lost its
 * durable record, the cross-link pass never ran, and the caller saw an exception instead of a
 * {@code PushResult}. These tests pin: per-repo record fault isolation with one retry, loud
 * surfacing of a created-but-unrecorded PR URL, cross-link execution over whatever PRs exist
 * (best-effort per PR), and cross-link idempotency.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmPushServiceMultiRepoRecordFaultIsolationTest {

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

  private static final String REPO_A = "acme/api";
  private static final String REPO_B = "acme/frontend";
  private static final String PR_A = "https://github.com/acme/api/pull/7";
  private static final String PR_B = "https://github.com/acme/frontend/pull/9";

  private final UUID repoAId = UUID.randomUUID();
  private final UUID repoBId = UUID.randomUUID();

  private PipelineRun twoRepoRun() throws Exception {
    org.mockito.Mockito.lenient()
        .when(publicationAdmissionService.assertEligible(any(PipelineRun.class)))
        .thenAnswer(
            ignored ->
                new PublicationAdmissionService.Admission("a".repeat(64), "PUBLICATION_ELIGIBLE"));
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setGithubPat("ghp_user_pat");
    when(appUserRepository.findById("user-1")).thenReturn(Optional.of(user));
    when(runRepoSnapshotRepository.findByRunId(anyString())).thenReturn(List.of());

    ProjectRepo repoA =
        ProjectRepo.builder().id(repoAId).githubRepoFullName(REPO_A).defaultBranch("main").build();
    ProjectRepo repoB =
        ProjectRepo.builder().id(repoBId).githubRepoFullName(REPO_B).defaultBranch("main").build();
    when(projectRepoRepository.findById(repoAId)).thenReturn(Optional.of(repoA));
    when(projectRepoRepository.findById(repoBId)).thenReturn(Optional.of(repoB));
    when(projectRepoRepository.findAllById(any())).thenReturn(List.of(repoA, repoB));

    when(gitTool.commitAndPushSubdir(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class)))
        .thenReturn(GitToolResult.ok("pushed"));
    when(gitHubRepoService.createPullRequestDetailed(
            eq("https://github.com/" + REPO_A),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString()))
        .thenReturn(GitHubRepoService.PullRequestCreation.created(PR_A, 7));
    when(gitHubRepoService.createPullRequestDetailed(
            eq("https://github.com/" + REPO_B),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString()))
        .thenReturn(GitHubRepoService.PullRequestCreation.created(PR_B, 9));

    PipelineRun run =
        PipelineRun.builder()
            .runId("run-multi")
            .createdByUserId("user-1")
            .scmProvider("GITHUB")
            .repoUrl("https://github.com/acme/api")
            .projectId("project-1")
            .selectedRepoIds(new ObjectMapper().writeValueAsString(List.of(repoAId, repoBId)))
            .userRequest("multi-repo change")
            .build();
    when(stateStore.findRun(run.getRunId())).thenReturn(Optional.of(run));
    return run;
  }

  private void stubRecordOk(String repoFullName, UUID repoId, String prUrl) {
    when(pullRequestRecordService.recordCreated(
            eq("run-multi"), eq(repoId), eq(repoFullName), eq(prUrl), anyString(), anyString()))
        .thenReturn(PullRequestRecord.builder().runId("run-multi").prUrl(prUrl).build());
  }

  @Test
  void secondRepoRecordPersistenceFailureKeepsFirstRecordAndSurfacesLostPrUrl() throws Exception {
    PipelineRun run = twoRepoRun();
    stubRecordOk(REPO_A, repoAId, PR_A);
    when(pullRequestRecordService.recordCreated(
            eq("run-multi"), eq(repoBId), eq(REPO_B), eq(PR_B), anyString(), anyString()))
        .thenThrow(new RuntimeException("db down"));

    ScmPushService.PushResult result = service.push(run);

    // The push itself succeeded for both repos — the caller must see a result, not an exception,
    // and the created-but-unrecorded PR URL must be surfaced for recovery.
    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.message()).contains(PR_B).contains("PR record persistence failed");
    // First repo's record was persisted and retained.
    verify(pullRequestRecordService)
        .recordCreated(
            eq("run-multi"), eq(repoAId), eq(REPO_A), eq(PR_A), anyString(), anyString());
    // Second repo's persistence was retried exactly once (2 attempts total).
    verify(pullRequestRecordService, times(2))
        .recordCreated(
            eq("run-multi"), eq(repoBId), eq(REPO_B), eq(PR_B), anyString(), anyString());
    // Cross-link pass still ran over BOTH PRs that exist on GitHub.
    verify(gitHubRepoService)
        .updatePullRequestBody(eq("https://github.com/" + REPO_A), eq(7), anyString(), anyString());
    verify(gitHubRepoService)
        .updatePullRequestBody(eq("https://github.com/" + REPO_B), eq(9), anyString(), anyString());
  }

  @Test
  void recordPersistenceRetriesOnceAndRecoversWithoutLoudNote() throws Exception {
    PipelineRun run = twoRepoRun();
    stubRecordOk(REPO_A, repoAId, PR_A);
    when(pullRequestRecordService.recordCreated(
            eq("run-multi"), eq(repoBId), eq(REPO_B), eq(PR_B), anyString(), anyString()))
        .thenThrow(new RuntimeException("transient"))
        .thenReturn(PullRequestRecord.builder().runId("run-multi").prUrl(PR_B).build());

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.message()).doesNotContain("PR record persistence failed");
    verify(pullRequestRecordService, times(2))
        .recordCreated(
            eq("run-multi"), eq(repoBId), eq(REPO_B), eq(PR_B), anyString(), anyString());
  }

  @Test
  void bothSuccessPersistsBothRecordsAndCrossLinksEachPrWithBothSiblings() throws Exception {
    PipelineRun run = twoRepoRun();
    stubRecordOk(REPO_A, repoAId, PR_A);
    stubRecordOk(REPO_B, repoBId, PR_B);

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(gitHubRepoService)
        .updatePullRequestBody(
            eq("https://github.com/" + REPO_A), eq(7), body.capture(), anyString());
    assertThat(body.getValue()).contains(PR_A).contains(PR_B);
    verify(gitHubRepoService)
        .updatePullRequestBody(eq("https://github.com/" + REPO_B), eq(9), anyString(), anyString());
  }

  @Test
  void crossLinkFailureForOnePrDoesNotSkipTheSibling() throws Exception {
    PipelineRun run = twoRepoRun();
    stubRecordOk(REPO_A, repoAId, PR_A);
    stubRecordOk(REPO_B, repoBId, PR_B);
    doThrow(new RuntimeException("github 502"))
        .when(gitHubRepoService)
        .updatePullRequestBody(eq("https://github.com/" + REPO_A), eq(7), anyString(), anyString());

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.status()).isEqualTo("PUSHED");
    verify(gitHubRepoService)
        .updatePullRequestBody(eq("https://github.com/" + REPO_B), eq(9), anyString(), anyString());
  }

  @Test
  void unexpectedExceptionOnSecondRepoIsIsolatedAndReportedAsPartial() throws Exception {
    PipelineRun run = twoRepoRun();
    stubRecordOk(REPO_A, repoAId, PR_A);
    when(gitTool.commitAndPushSubdir(
            anyString(),
            eq("repos/frontend"),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class)))
        .thenThrow(new RuntimeException("git process crashed"));

    ScmPushService.PushResult result = service.push(run);

    // Repo A's PR + record survive; repo B's failure is counted, not propagated.
    assertThat(result.status()).isEqualTo("PARTIAL");
    assertThat(result.prUrl()).isEqualTo(PR_A);
    verify(pullRequestRecordService)
        .recordCreated(
            eq("run-multi"), eq(repoAId), eq(REPO_A), eq(PR_A), anyString(), anyString());
  }

  @Test
  void runningTheCrossLinkPassTwiceNeverDuplicatesTheSiblingSection() {
    List<ScmPushService.CreatedPr> created =
        List.of(
            new ScmPushService.CreatedPr(REPO_A, PR_A, 7, false, false, true),
            new ScmPushService.CreatedPr(REPO_B, PR_B, 9, false, false, true));

    service.applyCrossLinks("run-multi", "base body", created, "ghp_user_pat");
    service.applyCrossLinks("run-multi", "base body", created, "ghp_user_pat");

    ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
    verify(gitHubRepoService, times(2))
        .updatePullRequestBody(
            eq("https://github.com/" + REPO_A), eq(7), bodies.capture(), anyString());
    List<String> captured = bodies.getAllValues();
    assertThat(captured.get(0)).isEqualTo(captured.get(1));
    assertThat(countOccurrences(captured.get(1), "**Multi-repo change**")).isEqualTo(1);
  }

  @Test
  void buildCrossLinkedBodyIsIdempotentOverItsOwnOutput() {
    String siblings = "- " + REPO_A + ": " + PR_A + "\n- " + REPO_B + ": " + PR_B;
    String once = ScmPushService.buildCrossLinkedBody("base body", siblings);
    String twice = ScmPushService.buildCrossLinkedBody(once, siblings);

    assertThat(twice).isEqualTo(once);
    assertThat(countOccurrences(twice, "**Multi-repo change**")).isEqualTo(1);
    assertThat(once).startsWith("base body");
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) >= 0) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
