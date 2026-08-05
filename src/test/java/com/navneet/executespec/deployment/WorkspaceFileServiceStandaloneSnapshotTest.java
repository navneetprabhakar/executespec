package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileServiceStandaloneSnapshotTest {

  @TempDir Path workspace;

  @Test
  void rootSnapshotProvidesRealBaseDiffAndFileStatuses() throws Exception {
    String runId = "standalone-run";
    Path root = Files.createDirectories(workspace.resolve(runId));
    git(root, "init", "-b", "main");
    git(root, "config", "user.email", "test@example.com");
    git(root, "config", "user.name", "Test");
    Files.writeString(root.resolve("unchanged.js"), "export const same = 1;\n");
    Files.writeString(root.resolve("modified.js"), "export const value = 1;\n");
    Files.createDirectories(root.resolve("repos/standalone"));
    Files.writeString(root.resolve("repos/standalone/nested.js"), "export const nested = true;\n");
    git(root, "add", ".");
    git(root, "commit", "-m", "baseline");
    String baseline = output(root, "rev-parse", "HEAD");
    Files.writeString(root.resolve("modified.js"), "export const value = 2;\n");
    Files.writeString(root.resolve("added.js"), "export const added = true;\n");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(runId))
        .thenReturn(
            List.of(
                RunRepoSnapshot.builder()
                    .runId(runId)
                    .repoFullName("acme/standalone")
                    .branch("main")
                    .commitSha(baseline)
                    .build()));
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    WorkspaceFileService service =
        new WorkspaceFileService(
            properties,
            mock(StateStore.class),
            mock(GitHubRepoService.class),
            mock(WorkspaceRestoreService.class),
            snapshots);

    var diff = service.readFileDiff(runId, "modified.js");
    assertThat(diff.base()).isEqualTo("export const value = 1;\n");
    assertThat(diff.head()).isEqualTo("export const value = 2;\n");
    assertThat(diff.status()).isEqualTo(WorkspaceFileService.Status.MODIFIED);
    assertThat(service.listFilesWithStatus(runId))
        .extracting(WorkspaceFileService.FileEntry::path, WorkspaceFileService.FileEntry::status)
        .contains(
            org.assertj.core.groups.Tuple.tuple(
                "unchanged.js", WorkspaceFileService.Status.UNCHANGED),
            org.assertj.core.groups.Tuple.tuple(
                "modified.js", WorkspaceFileService.Status.MODIFIED),
            org.assertj.core.groups.Tuple.tuple("added.js", WorkspaceFileService.Status.ADDED));
  }

  private static void git(Path root, String... arguments) throws Exception {
    output(root, arguments);
  }

  private static String output(Path root, String... arguments) throws Exception {
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    if (process.waitFor() != 0) throw new AssertionError(output);
    return output;
  }
}
