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

  @BeforeEach
  void setUp() throws Exception {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(tempDir.toString());
    workspace = tempDir.resolve("run-1");
    Files.createDirectories(workspace);
    stateStore = mock(StateStore.class);
    gitHubRepoService = mock(GitHubRepoService.class);
    restoreService = mock(WorkspaceRestoreService.class);
    service =
        new WorkspaceFileService(
            properties,
            stateStore,
            gitHubRepoService,
            restoreService,
            mock(RunRepoSnapshotRepository.class));
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
}
