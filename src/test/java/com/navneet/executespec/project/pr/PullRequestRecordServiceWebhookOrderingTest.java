package com.navneet.executespec.project.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.notification.InAppNotificationService;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-911 — a delayed or duplicate {@code closed} webhook must not downgrade an already-MERGED PR.
 * Merge is a terminal, monotonic transition; a stale close delivery is ignored, not applied.
 */
class PullRequestRecordServiceWebhookOrderingTest {

  private final PullRequestRecordRepository repo = mock(PullRequestRecordRepository.class);
  private final PipelineRunRepository pipelineRuns = mock(PipelineRunRepository.class);
  private final PullRequestRecordService service =
      new PullRequestRecordService(
          repo,
          mock(InAppNotificationService.class),
          mock(NotificationRepository.class),
          pipelineRuns,
          mock(ProjectRepoRepository.class),
          mock(EventTrackingService.class),
          mock(ActivityEventEmitter.class));

  @Test
  void staleCloseWebhookDoesNotDowngradeAMergedPr() { // RR-911
    PullRequestRecord merged =
        PullRequestRecord.builder()
            .repoFullName("acme/repo")
            .prNumber(7)
            .prUrl("https://github.com/acme/repo/pull/7")
            .status(PullRequestRecord.Status.MERGED)
            .mergedAt(Instant.now().minusSeconds(60))
            .build();
    when(repo.findByRepoFullNameAndPrNumber("acme/repo", 7)).thenReturn(Optional.of(merged));

    service.applyWebhookUpdate("acme/repo", 7, false, Instant.now());

    assertThat(merged.getStatus()).isEqualTo(PullRequestRecord.Status.MERGED);
    verify(repo, never()).save(any());
  }

  @Test
  void existingPrCorrectionRunProjectsTheOriginalLivePrRecord() {
    PullRequestRecord existing =
        PullRequestRecord.builder()
            .runId("origin-run")
            .repoFullName("acme/repo")
            .prNumber(7)
            .prUrl("https://github.com/acme/repo/pull/7")
            .status(PullRequestRecord.Status.OPEN)
            .build();
    PipelineRun correction =
        PipelineRun.builder()
            .runId("fix-run")
            .publicationRepoFullName("acme/repo")
            .publicationPrNumber(7)
            .build();
    when(repo.findByRunIdOrderByCreatedAtAsc("fix-run")).thenReturn(java.util.List.of());
    when(pipelineRuns.findById("fix-run")).thenReturn(Optional.of(correction));
    when(repo.findByRepoFullNameAndPrNumber("acme/repo", 7)).thenReturn(Optional.of(existing));

    assertThat(service.listForRun("fix-run")).containsExactly(existing);
  }
}
