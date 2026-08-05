package com.navneet.executespec.project.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/**
 * P2 activity-feed gap (2026-08-05) — the polling refresh path flips OPEN→MERGED directly (it never
 * crosses {@code applyWebhookUpdate}), so poll-detected merges previously produced a notification +
 * cascade but no PR_MERGED activity event. The emission is guarded on the actual transition, so a
 * repeated refresh over the now-terminal record must not append a duplicate.
 */
@ExtendWith(MockitoExtension.class)
class PrRefreshServiceMergeActivityTest {

  @Mock PullRequestRecordRepository prRepo;
  @Mock PipelineRunRepository pipelineRunRepo;
  @Mock GitHubRepoService github;
  @Mock PostMergeCascadeService cascade;
  @Mock PullRequestRecordService recordService;

  private PrRefreshService service() {
    return new PrRefreshService(prRepo, pipelineRunRepo, github, cascade, recordService);
  }

  private PullRequestRecord openRecord() {
    return PullRequestRecord.builder()
        .id(UUID.randomUUID())
        .runId("run-1")
        .repoFullName("acme/repo")
        .prNumber(7)
        .prUrl("https://github.com/acme/repo/pull/7")
        .status(PullRequestRecord.Status.OPEN)
        .build();
  }

  private void stubProjectRuns() {
    when(pipelineRunRepo.findByProjectIdOrderByStartedAtDesc("project-1", PageRequest.of(0, 500)))
        .thenReturn(List.of(PipelineRun.builder().runId("run-1").build()));
    when(prRepo.findByProjectIdAndKindNotAndStatusOrderByCreatedAtDesc(
            "project-1", PullRequestRecord.Kind.RUN, PullRequestRecord.Status.OPEN))
        .thenReturn(List.of());
  }

  @Test
  void openToMergedTransitionEmitsPrMergedActivityOnce() {
    PullRequestRecord rec = openRecord();
    stubProjectRuns();
    when(prRepo.findByRunIdAndStatusOrderByCreatedAtAsc("run-1", PullRequestRecord.Status.OPEN))
        .thenReturn(List.of(rec));
    when(github.getPullRequestSnapshot("acme/repo", 7, null, 0))
        .thenReturn(
            new GitHubRepoService.PullRequestSnapshot(
                "t", "b", "closed", true, 1, 1, 1, "head", "main", null, null));
    lenient().when(cascade.resolveProjectId(any())).thenReturn(null);

    PrRefreshService.RefreshResult first = service().refreshProject("project-1", null, false);
    // Second sweep: the record is now terminal (MERGED) — the loop skips it, so no re-emission.
    PrRefreshService.RefreshResult second = service().refreshProject("project-1", null, false);

    assertThat(first.newlyMerged()).isEqualTo(1);
    assertThat(second.newlyMerged()).isZero();
    assertThat(rec.getStatus()).isEqualTo(PullRequestRecord.Status.MERGED);
    verify(recordService, times(1)).emitPrTransitionActivity(rec, true);
    verify(cascade, times(1)).onMerged(rec);
  }

  @Test
  void unchangedOpenPrEmitsNoTransitionActivity() {
    PullRequestRecord rec = openRecord();
    stubProjectRuns();
    when(prRepo.findByRunIdAndStatusOrderByCreatedAtAsc("run-1", PullRequestRecord.Status.OPEN))
        .thenReturn(List.of(rec));
    when(github.getPullRequestSnapshot(anyString(), anyInt(), any(), anyInt()))
        .thenReturn(
            new GitHubRepoService.PullRequestSnapshot(
                "t", "b", "open", false, 1, 1, 1, "head", "main", null, null));

    service().refreshProject("project-1", null, false);

    verify(recordService, times(0))
        .emitPrTransitionActivity(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void openToClosedTransitionEmitsPrClosedActivity() {
    PullRequestRecord rec = openRecord();
    stubProjectRuns();
    when(prRepo.findByRunIdAndStatusOrderByCreatedAtAsc("run-1", PullRequestRecord.Status.OPEN))
        .thenReturn(List.of(rec));
    when(github.getPullRequestSnapshot("acme/repo", 7, null, 0))
        .thenReturn(
            new GitHubRepoService.PullRequestSnapshot(
                "t", "b", "closed", false, 1, 1, 1, "head", "main", null, null));

    service().refreshProject("project-1", null, false);

    assertThat(rec.getStatus()).isEqualTo(PullRequestRecord.Status.CLOSED);
    verify(recordService, times(1)).emitPrTransitionActivity(rec, false);
  }
}
