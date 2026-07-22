package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-862 — the restored-workspace GitHub fallback must read the exact revision the run executed
 * against, never the repository's current default branch. Otherwise a historical run's files/diffs
 * can be served from today's {@code main}, silently hiding branch deletion / force-push and
 * destroying reproducibility.
 */
class WorkspaceFileServiceRevisionTest {

  private final RunRepoSnapshotRepository snapshotRepo = mock(RunRepoSnapshotRepository.class);

  private WorkspaceFileService service() {
    return new WorkspaceFileService(
        mock(AgentProperties.class),
        mock(StateStore.class),
        mock(GitHubRepoService.class),
        mock(WorkspaceRestoreService.class),
        snapshotRepo);
  }

  private PipelineRun runWithRepo(String url) {
    PipelineRun run = new PipelineRun();
    run.setRepoUrl(url);
    return run;
  }

  @Test
  void prefersImmutableCommitShaOverBranch() {
    RunRepoSnapshot snap =
        RunRepoSnapshot.builder()
            .runId("run-1")
            .repoFullName("acme/widget")
            .branch("feature/x")
            .commitSha("deadbeefcafe")
            .build();
    when(snapshotRepo.findByRunId("run-1")).thenReturn(List.of(snap));

    assertThat(service().resolveRunRef("run-1", runWithRepo("https://github.com/acme/widget")))
        .isEqualTo("deadbeefcafe");
  }

  @Test
  void fallsBackToBranchWhenNoSha() {
    RunRepoSnapshot snap =
        RunRepoSnapshot.builder()
            .runId("run-2")
            .repoFullName("acme/widget")
            .branch("release/2")
            .commitSha(null)
            .build();
    when(snapshotRepo.findByRunId("run-2")).thenReturn(List.of(snap));

    assertThat(service().resolveRunRef("run-2", runWithRepo("https://github.com/acme/widget.git")))
        .isEqualTo("release/2");
  }

  @Test
  void returnsNullWhenNoRevisionIdentity() {
    when(snapshotRepo.findByRunId("run-3")).thenReturn(List.of());

    // Null ⇒ the caller fails closed (evidence-unavailable) instead of substituting `main`.
    assertThat(service().resolveRunRef("run-3", runWithRepo("https://github.com/acme/widget")))
        .isNull();
  }

  @Test
  void matchesTheRightSnapshotInAMultiRepoRun() {
    RunRepoSnapshot a =
        RunRepoSnapshot.builder()
            .runId("run-4")
            .repoFullName("acme/frontend")
            .commitSha("aaa111")
            .build();
    RunRepoSnapshot b =
        RunRepoSnapshot.builder()
            .runId("run-4")
            .repoFullName("acme/backend")
            .commitSha("bbb222")
            .build();
    when(snapshotRepo.findByRunId("run-4")).thenReturn(List.of(a, b));

    assertThat(service().resolveRunRef("run-4", runWithRepo("https://github.com/acme/backend")))
        .isEqualTo("bbb222");
  }
}
