package com.navneet.executespec.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class StackRefreshJobBoundedTest {

  @Test
  void schedulerUsesOneBoundedDatabasePageAndNeverFindAll() {
    ProjectRepoRepository repos = mock(ProjectRepoRepository.class);
    when(repos.findStackRefreshCandidates(any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());
    StackRefreshJob job =
        new StackRefreshJob(
            repos,
            mock(ProjectRepoLinkRepository.class),
            mock(AppUserRepository.class),
            mock(StackDetector.class),
            mock(GitHubRepoService.class),
            new SimpleMeterRegistry());

    job.runOnce();

    verify(repos).findStackRefreshCandidates(any(Instant.class), any(Pageable.class));
    verify(repos, never()).findAll();
    assertThat(repos).isNotNull();
  }
}
