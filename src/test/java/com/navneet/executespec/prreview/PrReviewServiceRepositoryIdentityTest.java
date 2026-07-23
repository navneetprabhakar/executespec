package com.navneet.executespec.prreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

class PrReviewServiceRepositoryIdentityTest {

  private final PrReviewRepository reviewRepo = mock(PrReviewRepository.class);
  private final GitHubRepoService github = mock(GitHubRepoService.class);
  private PrReviewService service;

  @BeforeEach
  void setUp() {
    service =
        new PrReviewService(
            reviewRepo,
            mock(PrReviewCommentRepository.class),
            mock(PrReviewAgent.class),
            github,
            mock(RunCostTracker.class),
            mock(SseBroadcaster.class),
            mock(AppUserRepository.class),
            mock(TierConfigRepository.class),
            mock(ProjectProviderResolver.class));
  }

  @AfterEach
  void shutdown() {
    service.shutdown();
  }

  @Test
  void submitIdempotencyUsesDurableRepoIdNotMutableFullName() {
    UUID repoId = UUID.randomUUID();
    String sha = "a".repeat(40);
    AppUser user = AppUser.builder().id("user-1").build();
    PrReview existing =
        PrReview.builder()
            .projectId("project-1")
            .repoId(repoId)
            .repoFullName("org/old-name")
            .prNumber(42)
            .headSha(sha)
            .status("COMPLETE")
            .build();
    when(github.getPullRequestSnapshot("org/new-name", 42, null, 200_000))
        .thenReturn(snapshot(sha));
    when(reviewRepo.findByProjectIdAndRepoIdAndPrNumberAndHeadSha("project-1", repoId, 42, sha))
        .thenReturn(Optional.of(existing));

    PrReview result = service.submitReview("project-1", repoId, "org/new-name", 42, user);

    assertSame(existing, result);
    verify(reviewRepo).findByProjectIdAndRepoIdAndPrNumberAndHeadSha("project-1", repoId, 42, sha);
    verify(reviewRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  // RR-660 — a PR whose snapshot has no resolvable head SHA must be rejected, not persisted against
  // a fabricated "unknown-<millis>" marker that makes every retry a distinct billable review tied
  // to no immutable source.
  @Test
  void submitRejectsPrWithNoResolvableHeadSha() {
    UUID repoId = UUID.randomUUID();
    AppUser user = AppUser.builder().id("user-1").build();
    when(github.getPullRequestSnapshot("org/repo", 7, null, 200_000)).thenReturn(snapshot(null));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> service.submitReview("project-1", repoId, "org/repo", 7, user));

    verify(reviewRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void historyLookupUsesDurableRepoId() {
    UUID repoId = UUID.randomUUID();
    PrReview row = PrReview.builder().repoId(repoId).projectId("project-1").build();
    Instant since = Instant.parse("2026-01-01T00:00:00Z");
    PageRequest page = PageRequest.of(0, 50);
    when(reviewRepo
            .findByProjectIdAndRepoIdAndPrNumberAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
                "project-1", repoId, 42, since, page))
        .thenReturn(new SliceImpl<>(java.util.List.of(row), page, false));

    assertEquals(
        java.util.List.of(row),
        service.findByPr("project-1", repoId, 42, since, page).getContent());
    verify(reviewRepo)
        .findByProjectIdAndRepoIdAndPrNumberAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
            "project-1", repoId, 42, since, page);
  }

  private static GitHubRepoService.PullRequestSnapshot snapshot(String sha) {
    return new GitHubRepoService.PullRequestSnapshot(
        "title", "body", "open", false, 1, 0, 1, "head", "main", sha, "diff");
  }
}
