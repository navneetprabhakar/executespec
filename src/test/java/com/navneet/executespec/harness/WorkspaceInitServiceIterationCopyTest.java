package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceInitServiceIterationCopyTest {

  @TempDir Path workspace;

  @Test
  void requiredCopyCarriesTrackedRootAndNestedRepoButNotUntrackedFiles() throws Exception {
    Path source = workspace.resolve("source-run");
    initRepo(source);
    Files.writeString(source.resolve("README.md"), "source change\n");
    Files.writeString(source.resolve("secret.tmp"), "must not copy\n");

    Path nested = source.resolve("repos").resolve("api");
    initRepo(nested);
    Files.writeString(nested.resolve("Api.java"), "class Api { int version = 2; }\n");
    git(nested, "add", "Api.java");
    git(nested, "commit", "-m", "api");
    Files.writeString(nested.resolve("untracked.env"), "TOKEN=no\n");

    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId("source-run")).thenReturn(List.of());
    WorkspaceInitService service =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            snapshots,
            mock(VendoredPathGuard.class),
            mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class));

    service.copyFromRunRequired("source-run", "child-run");

    Path child = workspace.resolve("child-run");
    assertThat(Files.readString(child.resolve("README.md"))).isEqualTo("source change\n");
    assertThat(child.resolve("secret.tmp")).doesNotExist();
    assertThat(Files.readString(child.resolve("repos/api/Api.java"))).contains("version = 2");
    assertThat(child.resolve("repos/api/untracked.env")).doesNotExist();
    assertThat(child.resolve("repos/api/.git")).isDirectory();
  }

  private static void initRepo(Path directory) throws Exception {
    Files.createDirectories(directory);
    git(directory, "init", "-b", "main");
    git(directory, "config", "user.email", "test@executespec.local");
    git(directory, "config", "user.name", "ExecuteSpec Test");
    Files.writeString(directory.resolve("README.md"), "initial\n");
    git(directory, "add", "README.md");
    git(directory, "commit", "-m", "initial");
  }

  private static void git(Path directory, String... arguments) throws Exception {
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) throw new IllegalStateException(output);
  }
}
