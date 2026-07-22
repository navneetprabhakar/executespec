package com.navneet.executespec.project.workspace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-463 — the warmer must mirror the LATEST codemap per repo, never every retained historical
 * version to the same per-repo path (which could clobber current planner evidence with an older
 * version depending on DB iteration order).
 */
class ProjectWorkspaceWarmerLatestCodeMapTest {

  private final ProjectWorkspaceService workspace = mock(ProjectWorkspaceService.class);
  private final ProjectCodeMapRepository codeMapRepo = mock(ProjectCodeMapRepository.class);
  private final ProjectArchitectureDocRepository archRepo =
      mock(ProjectArchitectureDocRepository.class);
  private final ProjectRepoLinkRepository linkRepo = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepoRepository repoRepo = mock(ProjectRepoRepository.class);

  private final ProjectWorkspaceWarmer warmer =
      new ProjectWorkspaceWarmer(workspace, codeMapRepo, archRepo, linkRepo, repoRepo);

  @Test
  void warmsOnlyTheLatestCodeMapPerRepo() { // RR-463
    UUID repoId = UUID.randomUUID();
    when(linkRepo.findByProjectId("proj-1"))
        .thenReturn(List.of(ProjectRepoLink.builder().projectId("proj-1").repoId(repoId).build()));
    when(repoRepo.findById(repoId))
        .thenReturn(
            Optional.of(
                ProjectRepo.builder().id(repoId).githubRepoFullName("acme/widget").build()));
    when(codeMapRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc("proj-1", repoId))
        .thenReturn(
            Optional.of(
                ProjectCodeMap.builder()
                    .projectId("proj-1")
                    .repoId(repoId)
                    .version(12)
                    .content("latest codemap")
                    .build()));
    when(archRepo.findFirstByProjectIdOrderByVersionDesc("proj-1")).thenReturn(Optional.empty());

    warmer.warmDiskCache("proj-1");

    verify(workspace).writeRawFile("proj-1", "codemaps/widget.md", "latest codemap");
    // The whole-history load that produced the corruption must not be used.
    verify(codeMapRepo, never()).findByProjectId(any());
    verify(codeMapRepo, never()).findByProjectId(eq("proj-1"));
  }
}
