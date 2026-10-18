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
  void stagesStandaloneSnapshotFromTheRunWorkspaceRoot() throws Exception {
    Files.createDirectories(runRoot.resolve(".git"));
    Files.writeString(runRoot.resolve("app.js"), "standalone source");
    Files.createDirectories(runRoot.resolve("repos/standalone"));
    Files.writeString(runRoot.resolve("repos/standalone/bait.js"), "not the root application");
    when(snapshots.findByRunId("run-root"))
        .thenReturn(List.of(standaloneSnapshot("run-root", "owner/standalone")));

    Path staged = new PreviewWorkspaceResolver(snapshots).resolve("run-root", runRoot);

    assertThat(staged).isNotEqualTo(runRoot);
    assertThat(staged.resolve("app.js")).hasContent("standalone source");
  }

  @Test
  void restoredProjectPrefersExistingNestedRepositoryOverSyntheticRootGit() throws Exception {
    Files.createDirectories(runRoot.resolve(".git"));
    Path nested = Files.createDirectories(runRoot.resolve("repos/montecarlo"));
    Files.writeString(runRoot.resolve("wrapper.txt"), "not the application");
    Files.writeString(nested.resolve("app.js"), "restored source");
    when(snapshots.findByRunId("run-restored"))
        .thenReturn(List.of(snapshot("run-restored", "owner/montecarlo")));

    Path staged = new PreviewWorkspaceResolver(snapshots).resolve("run-restored", runRoot);

    assertThat(staged.resolve("app.js")).hasContent("restored source");
    assertThat(staged.resolve("wrapper.txt")).doesNotExist();
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

  @Test
  void boundContextPreservesItsSourceAfterCanonicalEditsAndCannotBeReusedAsCurrent()
      throws Exception {
    when(snapshots.findByRunId("bound-run")).thenReturn(List.of());
    try (var git = org.eclipse.jgit.api.Git.init().setDirectory(runRoot.toFile()).call()) {
      Files.writeString(runRoot.resolve("app.js"), "export const value = 1;\n");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("seed").setAuthor("Fixture", "fixture@example.invalid").call();
    }
    var resolver = new PreviewWorkspaceResolver(snapshots);
    var bound = resolver.resolveBound("bound-run", runRoot);
    try {
      assertThat(resolver.matchesGeneration(runRoot, bound.generation())).isTrue();
      Files.writeString(runRoot.resolve("app.js"), "export const value = 2;\n");
      assertThat(resolver.matchesGeneration(runRoot, bound.generation())).isFalse();
      assertThat(bound.workspace().resolve("app.js")).hasContent("export const value = 1;\n");
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(bound.workspace().getParent().toFile());
    }
  }

  private static RunRepoSnapshot snapshot(String runId, String fullName) {
    return RunRepoSnapshot.builder()
        .runId(runId)
        .repoId(java.util.UUID.randomUUID())
        .repoFullName(fullName)
        .branch("master")
        .commitSha("a".repeat(40))
        .build();
  }

  private static RunRepoSnapshot standaloneSnapshot(String runId, String fullName) {
    return RunRepoSnapshot.builder()
        .runId(runId)
        .repoFullName(fullName)
        .branch("master")
        .commitSha("a".repeat(40))
        .build();
  }
}
