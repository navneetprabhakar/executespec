package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.project.entity.ProjectRepo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * R-324 Checkpoint 3 — pins the planner's codemap-scoping behaviour so future edits can't quietly
 * break the launch-time repo filter.
 *
 * <p>The contract:
 *
 * <ul>
 *   <li>Empty / null {@code scopedRepoIds} = "no filter" (every linked project repo is in scope;
 *       legacy pre-R-324 default).
 *   <li>Non-empty scope = filter codemaps to those repos.
 *   <li>If the user picked repos that have NO codemaps yet, return a dedicated message naming the
 *       cause instead of silently masking it as the generic "(no codemaps)" empty state.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExecutionPlannerCodemapFilterTest {

  @Mock com.navneet.executespec.agent.LlmRouter llmRouter;
  @Mock com.navneet.executespec.router.ModelResolver modelResolver;
  @Mock com.navneet.executespec.execution.repository.ExecutionRunRepository runRepo;
  @Mock com.navneet.executespec.execution.repository.ExecutionSolutionRepository solutionRepo;
  @Mock com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository codeMapRepo;
  @Mock com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  @Mock com.navneet.executespec.memory.ProjectMemoryProvider memoryProvider;
  @Mock FileConflictDetector conflictDetector;
  @Mock com.navneet.executespec.sse.SseBroadcaster broadcaster;

  ExecutionPlannerService planner;

  private static final String PROJECT_ID = "proj-1";
  private final UUID repoA = UUID.randomUUID();
  private final UUID repoB = UUID.randomUUID();
  private final UUID repoC = UUID.randomUUID();

  @BeforeEach
  void wire() {
    planner =
        new ExecutionPlannerService(
            llmRouter,
            modelResolver,
            new ObjectMapper(),
            runRepo,
            solutionRepo,
            codeMapRepo,
            projectRepoRepo,
            memoryProvider,
            conflictDetector,
            broadcaster,
            new ExecutionRunCasUpdater(runRepo));
  }

  @Test
  void noFilter_everyCodemapIncluded() {
    stubProjectRepos();
    when(codeMapRepo.findByProjectId(PROJECT_ID))
        .thenReturn(
            List.of(
                codemap(repoA, "A content"),
                codemap(repoB, "B content"),
                codemap(repoC, "C content")));

    String result = planner.buildCodemapsText(PROJECT_ID, Set.of());

    assertThat(result).contains("A content", "B content", "C content");
  }

  @Test
  void nullScope_treatedAsNoFilter() {
    stubProjectRepos();
    when(codeMapRepo.findByProjectId(PROJECT_ID))
        .thenReturn(List.of(codemap(repoA, "A content"), codemap(repoB, "B content")));

    String result = planner.buildCodemapsText(PROJECT_ID, null);

    assertThat(result).contains("A content", "B content");
  }

  @Test
  void scopedToSubset_onlyMatchingCodemapsIncluded() {
    stubProjectRepos();
    when(codeMapRepo.findByProjectId(PROJECT_ID))
        .thenReturn(
            List.of(
                codemap(repoA, "A content"),
                codemap(repoB, "B content"),
                codemap(repoC, "C content")));

    String result = planner.buildCodemapsText(PROJECT_ID, Set.of(repoA, repoC));

    assertThat(result).contains("A content").contains("C content").doesNotContain("B content");
  }

  @Test
  void scopedToReposWithNoCodemaps_returnsDedicatedMessage() {
    // codemapRepo returns codemaps only for repoA, but the user
    // selected repoB which has none yet — the filter must not
    // silently fall back to "(no codemaps)" because that masks the
    // real cause (missing analyse step on those repos).
    when(codeMapRepo.findByProjectId(PROJECT_ID)).thenReturn(List.of(codemap(repoA, "A content")));

    String result = planner.buildCodemapsText(PROJECT_ID, Set.of(repoB));

    assertThat(result).contains("(no codemaps for the selected repos");
    assertThat(result).contains("analyse");
  }

  @Test
  void noCodemapsAtAll_returnsLegacyEmptyState() {
    when(codeMapRepo.findByProjectId(PROJECT_ID)).thenReturn(List.of());

    String result = planner.buildCodemapsText(PROJECT_ID, Set.of());

    assertThat(result).isEqualTo("(no codemaps)");
  }

  /**
   * Stubs projectRepoRepo so codemap-render can find a repo name; we don't care about exact naming,
   * just that the lookup doesn't NPE.
   */
  private void stubProjectRepos() {
    lenient()
        .when(projectRepoRepo.findById(any(UUID.class)))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              ProjectRepo r =
                  ProjectRepo.builder()
                      .id(id)
                      .scope(ProjectRepo.Scope.USER)
                      .githubRepoFullName("acme/" + id.toString().substring(0, 8))
                      .githubOwner("acme")
                      .build();
              return Optional.of(r);
            });
  }

  private static ProjectCodeMap codemap(UUID repoId, String content) {
    return ProjectCodeMap.builder().projectId(PROJECT_ID).repoId(repoId).content(content).build();
  }
}
