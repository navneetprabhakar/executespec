package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileServiceSecurityTest {

  @TempDir Path tempDir;

  private WorkspaceFileService service;
  private Path workspace;
  private StateStore stateStore;
  private GitHubRepoService gitHubRepoService;
  private WorkspaceRestoreService restoreService;
  private RunRepoSnapshotRepository snapshots;

  @BeforeEach
  void setUp() throws Exception {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(tempDir.toString());
    workspace = tempDir.resolve("run-1");
    Files.createDirectories(workspace);
    stateStore = mock(StateStore.class);
    gitHubRepoService = mock(GitHubRepoService.class);
    restoreService = mock(WorkspaceRestoreService.class);
    snapshots = mock(RunRepoSnapshotRepository.class);
    service =
        new WorkspaceFileService(
            properties, stateStore, gitHubRepoService, restoreService, snapshots);
  }

  @Test
  void sensitiveFilesAreAbsentFromListingAndDeniedOnDirectRead() throws Exception {
    Files.writeString(workspace.resolve(".env"), "SECRET=canary");
    Files.writeString(workspace.resolve(".npmrc"), "//registry/:_authToken=canary");
    Files.writeString(workspace.resolve(".env.example"), "SECRET=replace-me");
    Files.writeString(workspace.resolve("app.java"), "class App {}");

    assertThat(service.listFiles("run-1"))
        .containsExactlyInAnyOrder(".env.example", "app.java")
        .doesNotContain(".env", ".npmrc");
    assertThatThrownBy(() -> service.readFile("run-1", ".env"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("Credential-like");
    assertThat(service.readFile("run-1", ".env.example")).contains("replace-me");
  }

  @Test
  void symbolicLinkCannotReadOutsideWorkspace() throws Exception {
    Path outside = tempDir.resolve("outside.txt");
    Files.writeString(outside, "host-secret-canary");
    Files.createSymbolicLink(workspace.resolve("notes.txt"), outside);

    assertThat(service.listFiles("run-1")).doesNotContain("notes.txt");
    assertThatThrownBy(() -> service.readFile("run-1", "notes.txt"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("Symbolic-link");
  }

  @Test
  void absoluteAndTraversalPathsFailBeforeFilesystemAccess() {
    assertThatThrownBy(() -> service.readFile("run-1", "/etc/passwd"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> service.readFile("run-1", "../outside.txt"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void githubFallbackFiltersAndDeniesSensitivePathsBeforeRemoteRead() throws Exception {
    Files.delete(workspace);
    when(restoreService.ensureWorkspaceAvailable("run-1"))
        .thenThrow(new IllegalStateException("not archived"));
    PipelineRun run = PipelineRun.builder().runId("run-1").repoUrl("owner/repo").build();
    when(stateStore.findRun("run-1")).thenReturn(java.util.Optional.of(run));
    when(gitHubRepoService.listRepoFiles("owner/repo", null))
        .thenReturn(java.util.List.of("src/App.java", ".env", "certs/client.pem"));

    assertThat(service.listFiles("run-1")).containsExactly("src/App.java");
    assertThatThrownBy(() -> service.readFile("run-1", ".env"))
        .isInstanceOf(SecurityException.class);
    verify(gitHubRepoService, never()).readRepoFile("owner/repo", ".env", null);
  }

  /**
   * RR-859 — the diff endpoint never consulted the sensitive-path policy, so a credential file
   * deleted from the head was still served from history through {@code git show <baseSha>:<path>}.
   */
  @Test
  void diffRefusesCredentialPathsDeletedFromTheHeadButPresentInHistory() throws Exception {
    String secret = "SECRET=diff-history-canary";
    Path repo = workspace.resolve("repos").resolve("api");
    Files.createDirectories(repo);
    git(repo, "init", "-q");
    Files.writeString(repo.resolve(".env"), secret);
    Files.writeString(repo.resolve("app.java"), "class App {}");
    git(repo, "add", ".env", "app.java");
    git(repo, "-c", "user.name=T", "-c", "user.email=t@e.com", "commit", "-qm", "base");
    String head = gitOutput(repo, "rev-parse", "HEAD").trim();
    Files.delete(repo.resolve(".env"));
    Files.delete(repo.resolve("app.java"));
    when(snapshots.findByRunId("run-1"))
        .thenReturn(
            java.util.List.of(
                com.navneet.executespec.state.entity.RunRepoSnapshot.builder()
                    .runId("run-1")
                    .repoFullName("owner/api")
                    .commitSha(head)
                    .build()));

    assertThatThrownBy(() -> service.readFileDiff("run-1", "repos/api/.env"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("Credential-like")
        .hasMessageNotContaining(secret);

    // Control: the same history IS reachable for an ordinary file, so the refusal above is the
    // policy taking effect rather than a broken fixture.
    assertThat(service.readFileDiff("run-1", "repos/api/app.java").base()).contains("class App");
  }

  @Test
  void diffRejectsTraversalByExactSegmentsRatherThanSubstring() {
    assertThatThrownBy(() -> service.readFileDiff("run-1", "repos/api/../../../etc/passwd"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> service.readFileDiff("run-1", "/etc/passwd"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> service.readFileDiff("run-1", "repos/api/%2e%2e/secret"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> service.readFileDiff("run-1", "repos/api/certs/client.pem"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("Credential-like");
  }

  private static void git(Path repo, String... args) throws Exception {
    assertThat(gitOutput(repo, args)).doesNotStartWith("EXIT=");
  }

  private static String gitOutput(Path repo, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
    command.addAll(java.util.List.of(args));
    Process process =
        new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    return process.waitFor() != 0 ? "EXIT=" + output : output;
  }
}
