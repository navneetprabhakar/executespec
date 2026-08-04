package com.navneet.executespec.project.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.finding.Finding;
import com.navneet.executespec.finding.FindingPatchSubmitter;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CiFailureAutoFixServiceIdempotencyTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

  private final PullRequestRecordRepository prRepository = mock(PullRequestRecordRepository.class);
  private final CiFailureRunRepository runRepository = mock(CiFailureRunRepository.class);
  private final CiFailureDeliveryClaimStore claimStore = mock(CiFailureDeliveryClaimStore.class);
  private final FindingPatchSubmitter submitter = mock(FindingPatchSubmitter.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final CiFailureAutoFixService service =
      new CiFailureAutoFixService(
          prRepository, runRepository, claimStore, submitter, mock(AppUserRepository.class), flags);

  @Test
  void duplicateDeliveryRemainsDedupedAfterTheFirstPatchRunIsTerminal() {
    PullRequestRecord pr = pullRequest();
    PipelineRun original = originalRun();
    PipelineRun handled = new PipelineRun();
    handled.setRunId("patch-run-1");
    when(flags.isEnabled("ci.autofix.enabled")).thenReturn(true);
    when(prRepository.findFirstByRepoFullNameAndHeadBranchOrderByCreatedAtDesc(
            "acme/app", "feature"))
        .thenReturn(Optional.of(pr));
    when(runRepository.findById("origin-run")).thenReturn(Optional.of(original));
    when(claimStore.claim(any(), any(), any(), any(), any(Integer.class)))
        .thenReturn(
            new CiFailureDeliveryClaimStore.Claim(
                "key", 1, CiFailureDeliveryClaimStore.Outcome.ACQUIRED, null));
    when(runRepository.findFirstByTriggeredByFindingOrderByStartedAtDesc(any()))
        .thenReturn(Optional.of(handled));
    when(claimStore.complete(any(), any())).thenReturn(true);

    var result =
        service.onCheckRunFailed(
            "acme/app", "feature", "tests failed", "https://checks/77", "77", "a".repeat(40));

    assertThat(result.dispatched()).isFalse();
    assertThat(result.reason()).contains("already handled by run patch-run-1");
    assertThat(pr.getCiAutoFixAttempts()).isZero();
    verify(submitter, never()).submit(any());
  }

  @Test
  void oneCheckObservationGetsAStableFindingAndConsumesOneAttempt() {
    PullRequestRecord pr = pullRequest();
    when(flags.isEnabled("ci.autofix.enabled")).thenReturn(true);
    when(prRepository.findFirstByRepoFullNameAndHeadBranchOrderByCreatedAtDesc(
            "acme/app", "feature"))
        .thenReturn(Optional.of(pr));
    when(runRepository.findById("origin-run")).thenReturn(Optional.of(originalRun()));
    when(claimStore.claim(any(), any(), any(), any(), any(Integer.class)))
        .thenReturn(
            new CiFailureDeliveryClaimStore.Claim(
                "key", 1, CiFailureDeliveryClaimStore.Outcome.ACQUIRED, null));
    when(runRepository.findFirstByTriggeredByFindingOrderByStartedAtDesc(any()))
        .thenReturn(Optional.empty());
    when(claimStore.complete(any(), any())).thenReturn(true);
    when(submitter.submit(any()))
        .thenReturn(
            new FindingPatchSubmitter.SubmitResult(
                FindingPatchSubmitter.SubmitResult.Outcome.DISPATCHED, "patch-run-1", "ignored"));

    var result =
        service.onCheckRunFailed(
            "acme/app",
            "feature",
            "tests failed",
            "https://checks/77",
            "77",
            "abcdef1234567890ffffabcdef1234567890ffff");

    ArgumentCaptor<Finding> finding = ArgumentCaptor.forClass(Finding.class);
    verify(submitter).submit(finding.capture());
    assertThat(finding.getValue().id()).isEqualTo(pr.getId() + "|check-77|sha-abcdef1234567890");
    assertThat(finding.getValue().selectedRepoIds()).containsExactly(REPO_ID);
    assertThat(finding.getValue().branchOverride()).isEqualTo("feature");
    assertThat(finding.getValue().publicationRepoFullName()).isEqualTo("acme/app");
    assertThat(finding.getValue().publicationPrNumber()).isEqualTo(42);
    assertThat(finding.getValue().publicationHeadSha())
        .isEqualTo("abcdef1234567890ffffabcdef1234567890ffff");
    assertThat(result.dispatched()).isTrue();
    // V359 reserves the attempt inside the claim transaction; the stale DTO is not re-saved.
    assertThat(pr.getCiAutoFixAttempts()).isZero();
    verify(prRepository, never()).save(pr);
  }

  @Test
  void concurrentLoserNeverReachesRunLookupOrSubmission() {
    PullRequestRecord pr = pullRequest();
    when(flags.isEnabled("ci.autofix.enabled")).thenReturn(true);
    when(prRepository.findFirstByRepoFullNameAndHeadBranchOrderByCreatedAtDesc(
            "acme/app", "feature"))
        .thenReturn(Optional.of(pr));
    when(runRepository.findById("origin-run")).thenReturn(Optional.of(originalRun()));
    when(claimStore.claim(any(), any(), any(), any(), any(Integer.class)))
        .thenReturn(
            new CiFailureDeliveryClaimStore.Claim(
                "key", 7, CiFailureDeliveryClaimStore.Outcome.IN_PROGRESS, null));

    var result =
        service.onCheckRunFailed(
            "acme/app", "feature", "tests failed", "https://checks/77", "77", "a".repeat(40));

    assertThat(result.dispatched()).isFalse();
    assertThat(result.reason()).contains("already being handled");
    verify(runRepository, never()).findFirstByTriggeredByFindingOrderByStartedAtDesc(any());
    verify(submitter, never()).submit(any());
  }

  private static PullRequestRecord pullRequest() {
    return PullRequestRecord.builder()
        .id(UUID.fromString("2ab7ed75-8dcb-44ea-99e9-81384a647d4a"))
        .runId("origin-run")
        .repoId(REPO_ID)
        .repoFullName("acme/app")
        .prNumber(42)
        .prUrl("https://github.com/acme/app/pull/42")
        .headBranch("feature")
        .status(PullRequestRecord.Status.OPEN)
        .ciAutoFixAttempts(0)
        .build();
  }

  private static PipelineRun originalRun() {
    PipelineRun run = new PipelineRun();
    run.setRunId("origin-run");
    run.setCreatedByUserId("user-1");
    run.setProjectId("project-1");
    run.setSelectedRepoIds("[]");
    run.setAiProvider("ANTHROPIC");
    return run;
  }
}
