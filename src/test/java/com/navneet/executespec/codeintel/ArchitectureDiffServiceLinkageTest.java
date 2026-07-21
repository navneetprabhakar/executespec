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
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.ArchitectureDiffService.DiffReport;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.GitHubRepoService.ChangedFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RR-1589 — the persisted architecture document names the repos it was generated from; it is not an
 * authority over which repos this project may still read. And an empty GitHub compare is not proof
 * that nothing changed.
 */
class ArchitectureDiffServiceLinkageTest {

  private ProjectArchitectureDocRepository docRepo;
  private GitHubRepoService gitHub;
  private ProjectRepoLinkRepository linkRepo;
  private ProjectRepoRepository repoRepo;
  private ArchitectureDiffService service;

  @BeforeEach
  void setUp() {
    docRepo = mock(ProjectArchitectureDocRepository.class);
    gitHub = mock(GitHubRepoService.class);
    linkRepo = mock(ProjectRepoLinkRepository.class);
    repoRepo = mock(ProjectRepoRepository.class);
    service =
        new ArchitectureDiffService(
            docRepo, gitHub, mock(AppUserRepository.class), linkRepo, repoRepo, new ObjectMapper());
  }

  private void docs(String fromShasJson, String toShasJson) {
    ProjectArchitectureDoc from = new ProjectArchitectureDoc();
    from.setGeneratedFromShas(fromShasJson);
    ProjectArchitectureDoc to = new ProjectArchitectureDoc();
    to.setGeneratedFromShas(toShasJson);
    when(docRepo.findByProjectIdAndVersion("p1", 1)).thenReturn(Optional.of(from));
    when(docRepo.findByProjectIdAndVersion("p1", 2)).thenReturn(Optional.of(to));
  }

  private void linked(String... fullNames) {
    java.util.List<ProjectRepoLink> links = new java.util.ArrayList<>();
    for (String name : fullNames) {
      UUID id = UUID.randomUUID();
      links.add(ProjectRepoLink.builder().projectId("p1").repoId(id).build());
      when(repoRepo.findById(id))
          .thenReturn(
              Optional.of(
                  ProjectRepo.builder()
                      .id(id)
                      .scope(ProjectRepo.Scope.USER)
                      .ownerUserId("u1")
                      .githubRepoFullName(name)
                      .githubOwner(name.split("/")[0])
                      .build()));
    }
    when(linkRepo.findByProjectId("p1")).thenReturn(links);
  }

  @Test
  void unlinkedRepoNamedByHistoricalDocumentIsNeverFetched() {
    docs(
        "{\"acme/api\":\"aaa\",\"acme/gone\":\"ccc\"}",
        "{\"acme/api\":\"bbb\",\"acme/gone\":\"ddd\"}");
    linked("acme/api"); // acme/gone was unlinked after v2 was generated
    when(gitHub.compareCommits(anyString(), anyString(), anyString(), any()))
        .thenReturn(
            GitHubRepoService.CompareResult.success(
                List.of(new ChangedFile("src/A.java", "modified", 3, 1))));

    DiffReport report = service.diff("p1", 1, 2, null);

    verify(gitHub).compareCommits("acme/api", "aaa", "bbb", null);
    verify(gitHub, never())
        .compareCommits(org.mockito.ArgumentMatchers.eq("acme/gone"), any(), any(), any());
    assertThat(report.skipped()).hasSize(1);
    assertThat(report.skipped().get(0).repoFullName()).isEqualTo("acme/gone");
    assertThat(report.skipped().get(0).status()).isEqualTo("NOT_LINKED");
  }

  /**
   * RR-1656 — this test previously asserted that an EMPTY compare is unverified, because the old
   * List-returning API could not tell a failed compare from a genuine no-change one. Now it can, so
   * the assertion is split: a FAILED compare is the unverified case.
   */
  @Test
  void failedCompareIsReportedAsUnverifiedNotAsZeroChanges() {
    docs("{\"acme/api\":\"aaa\"}", "{\"acme/api\":\"bbb\"}");
    linked("acme/api");
    when(gitHub.compareCommits(anyString(), anyString(), anyString(), any()))
        .thenReturn(GitHubRepoService.CompareResult.failed("GITHUB_COMPARE_HTTP_ERROR", 403));

    DiffReport report = service.diff("p1", 1, 2, null);

    assertThat(report.totalFiles()).isZero();
    assertThat(report.perRepo()).isEmpty();
    assertThat(report.unverified()).hasSize(1);
    assertThat(report.unverified().get(0).status()).isEqualTo("COMPARE_FAILED");
  }

  /** RR-1656 — a compare that ran and legitimately found nothing is NOT unverified. */
  @Test
  void successfulEmptyCompareIsAVerifiedNoChangeNotUnverified() {
    docs("{\"acme/api\":\"aaa\"}", "{\"acme/api\":\"bbb\"}");
    linked("acme/api");
    when(gitHub.compareCommits(anyString(), anyString(), anyString(), any()))
        .thenReturn(GitHubRepoService.CompareResult.success(List.of()));

    DiffReport report = service.diff("p1", 1, 2, null);

    assertThat(report.totalFiles()).isZero();
    assertThat(report.perRepo()).isEmpty();
    assertThat(report.unverified()).isEmpty();
  }

  @Test
  void identicalShasNeedNoCallAndAreNotFlaggedUnverified() {
    docs("{\"acme/api\":\"aaa\"}", "{\"acme/api\":\"aaa\"}");
    linked("acme/api");

    DiffReport report = service.diff("p1", 1, 2, null);

    verify(gitHub, never()).compareCommits(anyString(), anyString(), anyString(), any());
    assertThat(report.unverified()).isEmpty();
    assertThat(report.skipped()).isEmpty();
    assertThat(report.totalFiles()).isZero();
  }
}
