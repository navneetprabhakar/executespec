package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreviewWorkspaceResolverTest {

  @Mock RunRepoSnapshotRepository snapshots;
  @TempDir Path runRoot;

  @Test
  void resolvesSingleProjectRunToItsRepositoryCheckout() throws Exception {
    Path repo = Files.createDirectories(runRoot.resolve("repos/montecarlo"));
    when(snapshots.findByRunId("run-1"))
        .thenReturn(List.of(snapshot("run-1", "navneetprabhakar/montecarlo")));

    Files.writeString(repo.resolve("app.js"), "source");
    Path staged = new PreviewWorkspaceResolver(snapshots).resolve("run-1", runRoot);

    assertThat(staged).isNotEqualTo(repo.toRealPath());
    assertThat(staged.resolve("app.js")).hasContent("source");
    Files.writeString(staged.resolve("Dockerfile"), "generated");
    assertThat(repo.resolve("Dockerfile")).doesNotExist();
  }

  @Test
  void stagesLegacySingleRootWorkspaceWhenNoSnapshotExists() throws Exception {
    when(snapshots.findByRunId("run-1")).thenReturn(List.of());
    Files.writeString(runRoot.resolve("app.js"), "source");

    Path staged = new PreviewWorkspaceResolver(snapshots).resolve("run-1", runRoot);
    assertThat(staged).isNotEqualTo(runRoot);
    assertThat(staged.resolve("app.js")).hasContent("source");
  }

  @Test
  void failsClosedForMultiRepositoryRun() {
    when(snapshots.findByRunId("run-1"))
        .thenReturn(List.of(snapshot("run-1", "owner/one"), snapshot("run-1", "owner/two")));

    assertThatThrownBy(() -> new PreviewWorkspaceResolver(snapshots).resolve("run-1", runRoot))
        .isInstanceOf(PreviewException.class)
        .hasMessageContaining("exactly one repository");
  }

  @Test
  void rejectsSymlinkedRepositoryRoot() throws Exception {
    Path outside = Files.createDirectories(runRoot.resolveSibling(runRoot.getFileName() + "-out"));
    Files.createDirectories(runRoot.resolve("repos"));
    Files.createSymbolicLink(runRoot.resolve("repos/montecarlo"), outside);
    when(snapshots.findByRunId("run-1")).thenReturn(List.of(snapshot("run-1", "owner/montecarlo")));

    assertThatThrownBy(() -> new PreviewWorkspaceResolver(snapshots).resolve("run-1", runRoot))
        .isInstanceOf(PreviewException.class)
        .hasMessageContaining("unsafe for preview");
  }

  private static RunRepoSnapshot snapshot(String runId, String fullName) {
    return RunRepoSnapshot.builder()
        .runId(runId)
        .repoFullName(fullName)
        .branch("master")
        .commitSha("a".repeat(40))
        .build();
  }
}
