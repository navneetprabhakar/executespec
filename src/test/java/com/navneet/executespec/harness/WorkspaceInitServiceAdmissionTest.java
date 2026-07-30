package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceInitServiceAdmissionTest {

  @TempDir Path workspace;

  @Test
  void admitsOnlyWhenEverySelectedRepositoryHasGitMetadataAndProductFiles() throws Exception {
    WorkspaceInitService service = service();
    Path first = workspace.resolve("run-1/repos/api");
    Path second = workspace.resolve("run-1/repos/web");
    Files.createDirectories(first.resolve(".git"));
    Files.createDirectories(second.resolve(".git"));
    Files.writeString(first.resolve("Application.java"), "class Application {}");
    Files.writeString(second.resolve("package.json"), "{}");

    var admission =
        service.verifyMultipleRepos(
            "run-1",
            List.of(
                new WorkspaceInitService.RepoToClone("acme/api", null),
                new WorkspaceInitService.RepoToClone("acme/web", null)));

    assertTrue(admission.admitted());
    assertEquals(2, admission.fileCount());
  }

  @Test
  void rejectsMissingCloneInsteadOfTreatingOuterSeedAsBrownfieldEvidence() throws Exception {
    WorkspaceInitService service = service();
    Path seed = workspace.resolve("run-2");
    Files.createDirectories(seed.resolve(".git"));
    Files.writeString(seed.resolve("README.md"), "seed");

    var admission =
        service.verifyMultipleRepos(
            "run-2", List.of(new WorkspaceInitService.RepoToClone("acme/api", null)));

    assertFalse(admission.admitted());
    assertTrue(admission.reason().contains("clone missing"));
  }

  @Test
  void rejectsRepositoryContainingOnlyHiddenMetadata() throws Exception {
    WorkspaceInitService service = service();
    Path repo = workspace.resolve("run-3/repos/api");
    Files.createDirectories(repo.resolve(".git"));
    Files.createDirectories(repo.resolve(".github/workflows"));
    Files.writeString(repo.resolve(".github/workflows/ci.yml"), "name: ci");

    var admission =
        service.verifyMultipleRepos(
            "run-3", List.of(new WorkspaceInitService.RepoToClone("acme/api", null)));

    assertFalse(admission.admitted());
    assertTrue(admission.reason().contains("no source files"));
  }

  @Test
  void rejectsTwoSelectedRepositoriesThatWouldShareOneCheckoutDirectory() {
    WorkspaceInitService service = service();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.cloneMultipleRepos(
                "run-4",
                List.of(
                    new WorkspaceInitService.RepoToClone("org-a/api", null),
                    new WorkspaceInitService.RepoToClone("org-b/api", null)),
                null));
  }

  // RR-152 — the checkout directory is a single contained segment; a repository full name whose
  // last
  // segment is a traversal / reserved token must be rejected before it becomes a directory name.
  @Test
  void repoDirectoryNameRejectsTraversalAndReservedSegments() {
    assertEquals("repo", WorkspaceInitService.repoDirectoryName("owner/repo"));
    assertEquals("my.repo", WorkspaceInitService.repoDirectoryName("owner/my.repo"));
    assertThrows(SecurityException.class, () -> WorkspaceInitService.repoDirectoryName("owner/.."));
    assertThrows(SecurityException.class, () -> WorkspaceInitService.repoDirectoryName("owner/."));
  }

  // RR-152 — the run id is the only caller-derived segment appended to the workspace root; a
  // traversal id must be refused at the filesystem edge, while a normal UUID resolves under the
  // root.
  @Test
  void runWorkspacePathRejectsTraversalRunIds() {
    WorkspaceInitService service = service();
    Path resolved = service.runWorkspacePath("11111111-2222-3333-4444-555555555555");
    assertTrue(resolved.startsWith(workspace.toAbsolutePath().normalize()));
    // Traversal escapes are refused; a multi-segment id that stays UNDER the root is fine (still
    // contained), so only the traversal cases must throw.
    assertThrows(SecurityException.class, () -> service.runWorkspacePath("../escape"));
    assertThrows(SecurityException.class, () -> service.runWorkspacePath("a/../../etc"));
  }

  @Test
  void readOnlyRepositoryCloneDoesNotRequireOrCreatePipelineRunEvidence() throws Exception {
    Path source = workspace.resolve("source");
    Files.createDirectories(source);
    run(source, "git", "init", "-b", "main");
    Files.writeString(source.resolve("App.java"), "class App {}\n");
    run(source, "git", "add", "App.java");
    run(
        source,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "-m",
        "seed");
    var baseline = mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.resolve("runs").toString());
    WorkspaceInitService service =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            mock(RunRepoSnapshotRepository.class),
            mock(VendoredPathGuard.class),
            baseline);

    service.cloneReadOnlyRepository("project-source", source.toString(), null, null);

    assertTrue(Files.exists(service.runWorkspacePath("project-source").resolve("App.java")));
    verifyNoInteractions(baseline);
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
    assertEquals(0, process.waitFor(), new String(process.getErrorStream().readAllBytes()));
  }

  private WorkspaceInitService service() {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    return new WorkspaceInitService(
        properties,
        mock(WorkspaceRestoreService.class),
        mock(RunRepoSnapshotRepository.class),
        mock(VendoredPathGuard.class),
        mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class));
  }
}
