package com.navneet.executespec.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * RR-894 — the destructive sweeps must age from when a run/PR actually became terminal, not from
 * when the PR row was created. A createdAt-only bound closes a PR/deletes a branch immediately for
 * work that only transitioned recently, contradicting the documented post-terminal grace.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleScmCleanupTerminalTimingTest {

  @Mock private PullRequestRecordRepository prRepo;
  @Mock private PipelineRunRepository runRepo;
  @Mock private AppUserRepository userRepo;
  @Mock private GitHubRepoService gitHub;

  private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
      new SimpleMeterRegistry();

  private StaleScmCleanupJob jobUnderTest() {
    return new StaleScmCleanupJob(prRepo, runRepo, userRepo, gitHub, meterRegistry);
  }

  private PullRequestRecord openPr(String runId, Instant createdAt) {
    return PullRequestRecord.builder()
        .id(UUID.randomUUID())
        .runId(runId)
        .repoFullName("acme/repo")
        .prNumber(42)
        .prUrl("https://github.com/acme/repo/pull/42")
        .headBranch("executespec/abc123")
        .status(PullRequestRecord.Status.OPEN)
        .createdAt(createdAt)
        .build();
  }

  @Test
  void closeSweepSkipsARunThatFailedOnlyRecentlyEvenIfThePrRowIsOld() { // RR-894
    PullRequestRecord pr = openPr("run-1", Instant.now().minus(Duration.ofDays(20)));
    when(prRepo.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            eq(PullRequestRecord.Status.OPEN), any(), any()))
        .thenReturn(List.of(pr));
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .status("FAILED")
            .createdByUserId("user-1")
            .completedAt(Instant.now()) // became terminal today — inside the grace window
            .build();
    when(runRepo.findById("run-1")).thenReturn(Optional.of(run));

    assertThat(jobUnderTest().closeStalePrs()).isZero();
    verify(gitHub, never()).closePullRequest(anyString(), anyInt(), anyString());
  }

  @Test
  void closeSweepClosesAPrWhoseRunFailedBeyondTheGraceWindow() { // RR-894
    PullRequestRecord pr = openPr("run-2", Instant.now().minus(Duration.ofDays(20)));
    when(prRepo.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            eq(PullRequestRecord.Status.OPEN), any(), any()))
        .thenReturn(List.of(pr));
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-2")
            .status("FAILED")
            .createdByUserId("user-1")
            .completedAt(Instant.now().minus(Duration.ofDays(20)))
            .build();
    when(runRepo.findById("run-2")).thenReturn(Optional.of(run));
    when(userRepo.findById("user-1"))
        .thenReturn(Optional.of(AppUser.builder().id("user-1").githubPat("pat").build()));
    when(gitHub.closePullRequest("acme/repo", 42, "pat")).thenReturn(true);

    assertThat(jobUnderTest().closeStalePrs()).isEqualTo(1);
  }

  @Test
  void branchSweepSkipsAPrMergedInsideTheGraceWindow() { // RR-894
    PullRequestRecord pr =
        PullRequestRecord.builder()
            .id(UUID.randomUUID())
            .runId("run-3")
            .repoFullName("acme/repo")
            .prNumber(42)
            .prUrl("https://github.com/acme/repo/pull/42")
            .headBranch("executespec/abc123")
            .status(PullRequestRecord.Status.MERGED)
            .createdAt(Instant.now().minus(Duration.ofDays(40)))
            .mergedAt(Instant.now()) // merged today — branch must be retained
            .build();
    when(prRepo.findStaleTerminalExecutespecBranches(
            eq(PullRequestRecord.Status.OPEN), any(), any()))
        .thenReturn(List.of(pr));

    assertThat(jobUnderTest().deleteStaleBranches()).isZero();
    verify(gitHub, never()).getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt());
    verify(gitHub, never()).deleteBranch(anyString(), anyString(), anyString());
  }
}
