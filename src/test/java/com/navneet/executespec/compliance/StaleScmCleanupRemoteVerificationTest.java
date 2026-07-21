package com.navneet.executespec.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * RR-380 — the local {@code pull_request_record} row is a cached belief. Branch deletion is
 * irreversible, so every precondition is re-proved against the live remote and anything unproven
 * refuses.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleScmCleanupRemoteVerificationTest {

  @Mock private PullRequestRecordRepository prRepo;
  @Mock private PipelineRunRepository runRepo;
  @Mock private AppUserRepository userRepo;
  @Mock private GitHubRepoService gitHub;

  private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
      new SimpleMeterRegistry();

  private StaleScmCleanupJob jobUnderTest() {
    return new StaleScmCleanupJob(prRepo, runRepo, userRepo, gitHub, meterRegistry);
  }

  private PullRequestRecord record() {
    return PullRequestRecord.builder()
        .repoFullName("acme/repo")
        .prNumber(42)
        .prUrl("https://github.com/acme/repo/pull/42")
        .headBranch("executespec/abc123")
        .status(PullRequestRecord.Status.CLOSED)
        .build();
  }

  private GitHubRepoService.PullRequestSnapshot snapshot(String state, boolean merged, String sha) {
    return new GitHubRepoService.PullRequestSnapshot(
        "t", "b", state, merged, 0, 0, 0, "executespec/abc123", "main", sha, "");
  }

  @Test
  void deletesOnlyWhenTerminalStateNoOpenPrAndExactShaAreAllProved() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(snapshot("closed", true, "sha-1"));
    when(gitHub.findOpenPullRequestForHead(anyString(), anyString(), anyString()))
        .thenReturn(GitHubRepoService.OpenPullRequestLookup.none());
    when(gitHub.getBranchRef(anyString(), anyString(), anyString()))
        .thenReturn(GitHubRepoService.BranchRefState.at("sha-1"));

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat")).isNull();
  }

  @Test
  void refusesWhenTheRemotePullRequestIsActuallyStillOpen() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(snapshot("open", false, "sha-1"));

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat"))
        .isEqualTo("pr_reopened_or_open");
  }

  @Test
  void refusesWhenTheRemoteStateCannotBeRead() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(null);

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat"))
        .isEqualTo("pr_state_unknown");
  }

  @Test
  void refusesWhenAnotherOpenPullRequestNowReferencesTheBranch() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(snapshot("closed", false, "sha-1"));
    when(gitHub.findOpenPullRequestForHead(anyString(), anyString(), anyString()))
        .thenReturn(
            GitHubRepoService.OpenPullRequestLookup.found(
                "https://github.com/acme/repo/pull/77", 77));

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat"))
        .isEqualTo("open_pr_on_branch");
  }

  @Test
  void refusesWhenTheOpenPullRequestQuestionCannotBeAnswered() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(snapshot("closed", false, "sha-1"));
    when(gitHub.findOpenPullRequestForHead(anyString(), anyString(), anyString()))
        .thenReturn(GitHubRepoService.OpenPullRequestLookup.unknown());

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat"))
        .isEqualTo("open_pr_unknown");
  }

  @Test
  void refusesWhenCommitsLandedOnTheBranchAfterThePullRequestClosed() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(snapshot("closed", false, "sha-1"));
    when(gitHub.findOpenPullRequestForHead(anyString(), anyString(), anyString()))
        .thenReturn(GitHubRepoService.OpenPullRequestLookup.none());
    when(gitHub.getBranchRef(anyString(), anyString(), anyString()))
        .thenReturn(GitHubRepoService.BranchRefState.at("sha-2-new-work"));

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat"))
        .isEqualTo("branch_advanced_since_pr");
  }

  @Test
  void refusesWhenTheBranchRefCannotBeRead() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(snapshot("closed", false, "sha-1"));
    when(gitHub.findOpenPullRequestForHead(anyString(), anyString(), anyString()))
        .thenReturn(GitHubRepoService.OpenPullRequestLookup.none());
    when(gitHub.getBranchRef(anyString(), anyString(), anyString()))
        .thenReturn(GitHubRepoService.BranchRefState.unknown());

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat"))
        .isEqualTo("branch_ref_unknown");
  }

  /** A record describing a different head than the remote PR is not evidence about this branch. */
  @Test
  void refusesWhenTheRecordedBranchIsNotThePullRequestHead() {
    when(gitHub.getPullRequestSnapshot(anyString(), anyInt(), anyString(), anyInt()))
        .thenReturn(
            new GitHubRepoService.PullRequestSnapshot(
                "t", "b", "closed", true, 0, 0, 0, "executespec/other", "main", "sha-1", ""));

    assertThat(jobUnderTest().remoteDeleteRefusalReason(record(), "pat"))
        .isEqualTo("head_branch_mismatch");
  }
}
