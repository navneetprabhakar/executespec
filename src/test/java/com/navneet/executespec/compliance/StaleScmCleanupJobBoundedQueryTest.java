package com.navneet.executespec.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class StaleScmCleanupJobBoundedQueryTest {

  private final PullRequestRecordRepository prs = mock(PullRequestRecordRepository.class);
  private final StaleScmCleanupJob job =
      new StaleScmCleanupJob(
          prs,
          mock(PipelineRunRepository.class),
          mock(AppUserRepository.class),
          mock(GitHubRepoService.class),
          new SimpleMeterRegistry());

  @Test
  void bothSweepsUseDatabaseBoundedCandidatePagesInsteadOfFindAll() {
    when(prs.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            eq(PullRequestRecord.Status.OPEN), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(prs.findStaleTerminalExecutespecBranches(
            eq(PullRequestRecord.Status.OPEN), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());

    assertThat(job.closeStalePrs()).isZero();
    assertThat(job.deleteStaleBranches()).isZero();

    verify(prs, never()).findAll();
    verify(prs)
        .findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            eq(PullRequestRecord.Status.OPEN), any(Instant.class), any(Pageable.class));
    verify(prs)
        .findStaleTerminalExecutespecBranches(
            eq(PullRequestRecord.Status.OPEN), any(Instant.class), any(Pageable.class));
  }
}
