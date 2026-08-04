package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
            mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class),
            new WorkspaceRepositoryRevisionService(properties, snapshots));

    service.copyFromRunRequired("source-run", "child-run");

    Path child = workspace.resolve("child-run");
    assertThat(Files.readString(child.resolve("README.md"))).isEqualTo("source change\n");
    assertThat(child.resolve("secret.tmp")).doesNotExist();
    assertThat(Files.readString(child.resolve("repos/api/Api.java"))).contains("version = 2");
    assertThat(child.resolve("repos/api/untracked.env")).doesNotExist();
    assertThat(child.resolve("repos/api/.git")).isDirectory();
  }

  @Test
  void requiredCopyPersistsStandaloneRootSnapshotForTheIterationRun() throws Exception {
    Path source = workspace.resolve("source-root-run");
    initRepo(source);
    String sourceBaseline = output(source, "rev-parse", "HEAD");
    Files.writeString(source.resolve("README.md"), "iteration-ready\n");

    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    RunRepoSnapshot sourceSnapshot =
        RunRepoSnapshot.builder()
            .runId("source-root-run")
            .repoFullName("acme/standalone")
            .branch("main")
            .commitSha(sourceBaseline)
            .build();
    when(snapshots.findByRunId("source-root-run")).thenReturn(List.of(sourceSnapshot));
    when(snapshots.findByRunId("child-root-run")).thenReturn(List.of());
    WorkspaceInitService service =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            snapshots,
            mock(VendoredPathGuard.class),
            mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class),
            new WorkspaceRepositoryRevisionService(properties, snapshots));

    service.copyFromRunRequired("source-root-run", "child-root-run");

    Path child = workspace.resolve("child-root-run");
    assertThat(child.resolve("README.md")).hasContent("iteration-ready\n");
    ArgumentCaptor<RunRepoSnapshot> persisted = ArgumentCaptor.forClass(RunRepoSnapshot.class);
    verify(snapshots).save(persisted.capture());
    assertThat(persisted.getValue().getRunId()).isEqualTo("child-root-run");
    assertThat(persisted.getValue().getRepoFullName()).isEqualTo("acme/standalone");
    assertThat(persisted.getValue().getCommitSha()).isEqualTo(output(child, "rev-parse", "HEAD"));
  }

  @Test
  void requiredCopyFailsClosedWhenRestoredNestedRepositoryHasNoGitProof() throws Exception {
    Path source = workspace.resolve("restored-project-run");
    initRepo(source);
    Path nested = Files.createDirectories(source.resolve("repos/api"));
    Files.writeString(nested.resolve("Api.java"), "class Api { int restored = 1; }\n");
    git(source, "add", "repos/api/Api.java");
    git(source, "commit", "-m", "synthetic restored archive");

    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    RunRepoSnapshot sourceSnapshot =
        RunRepoSnapshot.builder()
            .runId("restored-project-run")
            .repoId(java.util.UUID.randomUUID())
            .repoFullName("acme/api")
            .branch("main")
            .commitSha("a".repeat(40))
            .build();
    when(snapshots.findByRunId("restored-project-run")).thenReturn(List.of(sourceSnapshot));
    when(snapshots.findByRunId("restored-child")).thenReturn(List.of());
    WorkspaceInitService service =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            snapshots,
            mock(VendoredPathGuard.class),
            mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class),
            new WorkspaceRepositoryRevisionService(properties, snapshots));

    assertThatThrownBy(() -> service.copyFromRunRequired("restored-project-run", "restored-child"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source repository Git metadata unavailable after restore: acme/api");
    org.mockito.Mockito.verify(snapshots, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any());
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

  private static String output(Path directory, String... arguments) throws Exception {
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    int exit = process.waitFor();
    if (exit != 0) throw new IllegalStateException(output);
    return output;
  }
}
