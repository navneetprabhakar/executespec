package com.navneet.executespec.project.pr;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;

/**
 * RR-519 — the caller-supplied {@code maxChars} preview bound must be clamped to a small server
 * constant so a caller cannot force an arbitrarily large PR diff to be buffered and returned.
 */
class PullRequestControllerSnapshotTest {

  private final GitHubRepoService gitHub = mock(GitHubRepoService.class);
  private final PullRequestController controller =
      new PullRequestController(
          mock(PullRequestRecordService.class),
          gitHub,
          mock(PrRefreshService.class),
          mock(RunAccessGuard.class),
          mock(ProjectAccessGuard.class));

  @Test
  void oversizedMaxCharsIsClampedToTheServerCeiling() { // RR-519
    when(gitHub.getPullRequestSnapshot(
            eq("acme/repo"), eq(5), isNull(), eq(PullRequestController.SNAPSHOT_MAX_DIFF_CHARS)))
        .thenReturn(null);

    controller.snapshot(null, "acme/repo", 5, 10_000_000);

    verify(gitHub)
        .getPullRequestSnapshot(
            "acme/repo", 5, null, PullRequestController.SNAPSHOT_MAX_DIFF_CHARS);
  }

  @Test
  void negativeMaxCharsIsClampedToZero() { // RR-519
    when(gitHub.getPullRequestSnapshot(eq("acme/repo"), eq(5), isNull(), eq(0))).thenReturn(null);

    controller.snapshot(null, "acme/repo", 5, -100);

    verify(gitHub).getPullRequestSnapshot("acme/repo", 5, null, 0);
  }
}
