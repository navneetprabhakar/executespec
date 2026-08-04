package com.navneet.executespec.prreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.finding.Finding;
import com.navneet.executespec.finding.FindingPatchSubmitter;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.entity.PrReviewComment;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PrReviewAutofixServiceTest {

  @Test
  void mergedPullRequestIsRejectedBeforeFindingSubmission() {
    UUID reviewId = UUID.randomUUID();
    UUID repoId = UUID.randomUUID();
    PrReviewRepository reviews = mock(PrReviewRepository.class);
    PrReviewCommentRepository comments = mock(PrReviewCommentRepository.class);
    ProjectRepoLinkRepository links = mock(ProjectRepoLinkRepository.class);
    ProjectRepoRepository repos = mock(ProjectRepoRepository.class);
    GitHubRepoService github = mock(GitHubRepoService.class);
    FindingPatchSubmitter submitter = mock(FindingPatchSubmitter.class);
    PrReviewAutofixService service =
        new PrReviewAutofixService(reviews, comments, links, repos, github, submitter);
    AppUser actor = AppUser.builder().id("actor-1").githubPat("ghp_actor").build();

    when(reviews.findById(reviewId))
        .thenReturn(
            Optional.of(
                PrReview.builder()
                    .id(reviewId)
                    .projectId("project-1")
                    .repoFullName("acme/app")
                    .prNumber(42)
                    .headSha("a".repeat(40))
                    .status("COMPLETE")
                    .verdict("REQUEST_CHANGES")
                    .build()));
    when(comments.findByPrReviewIdOrderByFilePathAscLineNumberAsc(reviewId))
        .thenReturn(
            List.of(
                PrReviewComment.builder()
                    .prReviewId(reviewId)
                    .filePath("src/App.java")
                    .lineNumber(7)
                    .side("RIGHT")
                    .body("Handle the failure")
                    .build()));
    when(links.findByProjectId("project-1"))
        .thenReturn(
            List.of(
                ProjectRepoLink.builder()
                    .projectId("project-1")
                    .repoId(repoId)
                    .linkedBy("actor-1")
                    .build()));
    when(repos.findById(repoId))
        .thenReturn(
            Optional.of(ProjectRepo.builder().id(repoId).githubRepoFullName("acme/app").build()));
    when(github.getPullRequestSnapshot("acme/app", 42, "ghp_actor", 0))
        .thenReturn(
            new GitHubRepoService.PullRequestSnapshot(
                "title",
                "body",
                "closed",
                true,
                1,
                0,
                1,
                "feature/fix",
                "main",
                "a".repeat(40),
                null));

    assertThatThrownBy(() -> service.dispatch(reviewId, actor))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(submitter, never()).submit(org.mockito.ArgumentMatchers.any(Finding.class));
  }
}
