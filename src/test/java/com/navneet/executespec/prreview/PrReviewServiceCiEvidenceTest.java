package com.navneet.executespec.prreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.reviewjob.ReviewBackgroundJobStore;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins that the durable PR-review job fetches the head SHA's CURRENT check-run status through the
 * existing {@link GitHubRepoService#getCheckRunRollup} path at review time and threads it into the
 * agent request (persona register P1 #4 — no more approvals blind to red CI).
 */
class PrReviewServiceCiEvidenceTest {

  private static final String SHA = "b".repeat(40);

  @Test
  void durableJobFetchesHeadCheckRunRollupAndThreadsItIntoTheAgentRequest() {
    PrReviewRepository reviewRepo = mock(PrReviewRepository.class);
    PrReviewCommentRepository commentRepo = mock(PrReviewCommentRepository.class);
    PrReviewAgent agent = mock(PrReviewAgent.class);
    GitHubRepoService github = mock(GitHubRepoService.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    AppUserRepository userRepo = mock(AppUserRepository.class);
    TierConfigRepository tierConfigRepo = mock(TierConfigRepository.class);
    ProjectProviderResolver providerResolver = mock(ProjectProviderResolver.class);
    ReviewBackgroundJobStore jobStore = mock(ReviewBackgroundJobStore.class);
    LlmExecutionPolicyFactory policyFactory = mock(LlmExecutionPolicyFactory.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    ProjectRepository projectRepository = mock(ProjectRepository.class);

    PrReviewService service =
        new PrReviewService(
            reviewRepo,
            commentRepo,
            agent,
            github,
            costTracker,
            broadcaster,
            userRepo,
            tierConfigRepo,
            providerResolver,
            jobStore,
            policyFactory,
            modelResolver,
            projectRepository,
            new ObjectMapper());

    UUID reviewId = UUID.randomUUID();
    ReviewBackgroundJobStore.Job job =
        new ReviewBackgroundJobStore.Job(
            UUID.randomUUID(),
            ReviewBackgroundJobStore.Type.PR_REVIEW,
            reviewId,
            "project-1",
            "org-1",
            UUID.randomUUID(),
            "user-1",
            "idem-1",
            "DEEPSEEK",
            "deepseek-v4-pro-native",
            "ctx-1",
            "CUSTOMER",
            "org-1",
            "{}",
            null,
            null,
            ReviewBackgroundJobStore.Status.RUNNING,
            1,
            1,
            Instant.now(),
            "owner",
            1L,
            Instant.now().plusSeconds(60),
            Instant.now(),
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            null);
    ReviewBackgroundJobStore.Lease lease =
        new ReviewBackgroundJobStore.Lease(job, "owner", 1L, Instant.now().plusSeconds(60));

    AppUser actor = AppUser.builder().build();
    actor.setId("user-1");
    actor.setGithubPat("pat-1");
    when(userRepo.findById("user-1")).thenReturn(Optional.of(actor));
    when(github.getCheckRunRollup("org/repo", SHA, "pat-1"))
        .thenReturn(new GitHubRepoService.CheckRunRollup("FAILURE", 2));
    when(jobStore.updatePrReviewStatus(any(), eq("RUNNING"))).thenReturn(true);
    when(reviewRepo.findById(reviewId)).thenReturn(Optional.of(PrReview.builder().build()));
    when(agent.review(any()))
        .thenReturn(
            new PrReviewAgent.AgentReview(
                "REQUEST_CHANGES", "CI failing on head …", List.of(), 5L, "deepseek", "m"));

    GitHubRepoService.PullRequestSnapshot snapshot =
        new GitHubRepoService.PullRequestSnapshot(
            "t", "b", "open", false, 1, 1, 1, "feature", "main", SHA, "diff");

    PrReviewService.DurablePrReviewResult result =
        service.runDurableJob(lease, "org/repo", 7, SHA, snapshot, null, () -> {});

    assertThat(result.agentReview().verdict()).isEqualTo("REQUEST_CHANGES");
    verify(github).getCheckRunRollup("org/repo", SHA, "pat-1");
    ArgumentCaptor<PrReviewAgent.AgentRequest> captor =
        ArgumentCaptor.forClass(PrReviewAgent.AgentRequest.class);
    verify(agent).review(captor.capture());
    assertThat(captor.getValue().ciStatus()).isEqualTo("FAILURE");
    assertThat(captor.getValue().ciFailingCount()).isEqualTo(2);
    assertThat(captor.getValue().headSha()).isEqualTo(SHA);
  }
}
