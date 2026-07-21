package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codeintel.StalenessService.RepoState;
import com.navneet.executespec.codeintel.StalenessService.StalenessReport;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * RR-1585 — a repo whose HEAD SHA cannot be probed, and a repo the GitHub metric cannot reach at
 * all, must be reported as their own explicit state instead of folding into "fresh"; and a report
 * that contains an unprobed repo must not be cached for every other viewer.
 */
class StalenessServiceUnknownStateTest {

  private ProjectArchitectureService archService;
  private ProjectRepoLinkRepository linkRepo;
  private ProjectRepoRepository repoRepo;
  private GitHubRepoService gitHub;
  private StringRedisTemplate redis;
  private ValueOperations<String, String> valueOps;
  private StalenessService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    archService = mock(ProjectArchitectureService.class);
    linkRepo = mock(ProjectRepoLinkRepository.class);
    repoRepo = mock(ProjectRepoRepository.class);
    gitHub = mock(GitHubRepoService.class);
    redis = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(anyString())).thenReturn(null);
    when(archService.latest(anyString())).thenReturn(Optional.empty());
    service =
        new StalenessService(archService, linkRepo, repoRepo, gitHub, new ObjectMapper(), redis);
  }

  private ProjectRepo githubRepo(UUID id, String fullName) {
    return ProjectRepo.builder()
        .id(id)
        .scope(ProjectRepo.Scope.USER)
        .ownerUserId("u1")
        .githubRepoFullName(fullName)
        .githubOwner(fullName.split("/")[0])
        .defaultBranch("main")
        .build();
  }

  private void link(UUID repoId) {
    when(linkRepo.findByProjectId("p1"))
        .thenReturn(List.of(ProjectRepoLink.builder().projectId("p1").repoId(repoId).build()));
  }

  @Test
  void unprobeableRepoIsUnknownNotFresh() {
    UUID repoId = UUID.randomUUID();
    link(repoId);
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(githubRepo(repoId, "acme/api")));
    // Probe fails — no PAT, rate limit, SCM outage, deleted repo.
    when(gitHub.getBranchHeadSha(anyString(), any(), any())).thenReturn(null);

    StalenessReport report = service.report("p1", null);

    assertThat(report.repos()).hasSize(1);
    assertThat(report.repos().get(0).state()).isEqualTo(RepoState.UNKNOWN);
    assertThat(report.repos().get(0).stale()).isFalse();
    assertThat(report.unknownCount()).isEqualTo(1);
    assertThat(report.totalCount()).isEqualTo(1);
    // The key assertion: the report does NOT claim to be a verified freshness verdict.
    assertThat(report.verified()).isFalse();
  }

  @Test
  void reportContainingUnknownIsNotCachedForOtherViewers() {
    UUID repoId = UUID.randomUUID();
    link(repoId);
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(githubRepo(repoId, "acme/api")));
    when(gitHub.getBranchHeadSha(anyString(), any(), any())).thenReturn(null);

    service.report("p1", null);

    verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));
  }

  @Test
  void fullyProbedReportIsCached() {
    UUID repoId = UUID.randomUUID();
    link(repoId);
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(githubRepo(repoId, "acme/api")));
    when(gitHub.getBranchHeadSha(anyString(), any(), any())).thenReturn("abc123");

    StalenessReport report = service.report("p1", "pat");

    // No codemap sha recorded for the repo => genuinely stale, and genuinely measured.
    assertThat(report.repos().get(0).state()).isEqualTo(RepoState.STALE);
    assertThat(report.unknownCount()).isZero();
    assertThat(report.verified()).isTrue();
    verify(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
  }

  @Test
  void localOnlyProjectIsNotMeasurableRatherThanEmptyAndFresh() {
    UUID repoId = UUID.randomUUID();
    link(repoId);
    ProjectRepo local = githubRepo(repoId, "local/folder");
    local.setSourceType(ProjectRepo.SourceType.LOCAL.name());
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(local));

    StalenessReport report = service.report("p1", "pat");

    assertThat(report.totalCount()).isEqualTo(1); // previously 0 — the repo was dropped entirely
    assertThat(report.notMeasurableCount()).isEqualTo(1);
    assertThat(report.repos().get(0).state()).isEqualTo(RepoState.NOT_MEASURABLE);
    assertThat(report.verified()).isFalse();
  }

  @Test
  void missingRepoRecordIsCountedNotSilentlyDropped() {
    UUID repoId = UUID.randomUUID();
    link(repoId);
    when(repoRepo.findById(repoId)).thenReturn(Optional.empty());

    StalenessReport report = service.report("p1", "pat");

    assertThat(report.totalCount()).isEqualTo(1);
    assertThat(report.notMeasurableCount()).isEqualTo(1);
    assertThat(report.verified()).isFalse();
  }
}
