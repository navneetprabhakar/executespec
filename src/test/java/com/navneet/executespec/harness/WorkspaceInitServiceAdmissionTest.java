package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
  void rejectsCrashRecoveredCloneOnDifferentBranchBeforeProviderDispatch() throws Exception {
    WorkspaceInitService service = service();
    Path repo = workspace.resolve("run-branch/repos/api");
    Files.createDirectories(repo);
    run(repo, "git", "init", "-b", "main");
    Files.writeString(repo.resolve("Application.java"), "class Application {}\n");

    var admission =
        service.verifyMultipleRepos(
            "run-branch",
            List.of(new WorkspaceInitService.RepoToClone("acme/api", null, null, "feature/pr-12")));

    assertFalse(admission.admitted());
    assertTrue(admission.reason().contains("branch mismatch"));
    assertTrue(admission.reason().contains("feature/pr-12"));
  }

  @Test
  void publicationHeadRejectsMissingNamedProjectCloneInsteadOfFallingBackToOuterRepository()
      throws Exception {
    Path run = workspace.resolve("run-missing-publication-clone");
    Files.createDirectories(run);
    run(run, "git", "init", "-b", "main");
    Files.writeString(run.resolve("README.md"), "outer seed\n");
    run(run, "git", "add", "README.md");
    run(
        run,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "-m",
        "outer seed");
    Files.createDirectories(run.resolve("repos/another-repo"));
    String outerHead = output(run, "git", "rev-parse", "HEAD");

    IllegalStateException missing =
        assertThrows(
            IllegalStateException.class,
            () ->
                service()
                    .requirePublicationHead(
                        "run-missing-publication-clone", "acme/expected-repo", outerHead));

    assertTrue(missing.getMessage().contains("expected-repo"));
    assertTrue(missing.getMessage().contains("clone is unavailable"));
  }

  @Test
  void clonesExactNonDefaultBranchAndPersistsItsVerifiedBaseline() throws Exception {
    Path source = workspace.resolve("source-pr");
    Files.createDirectories(source);
    run(source, "git", "init", "-b", "main");
    Files.writeString(source.resolve("Application.java"), "class Main {}\n");
    run(source, "git", "add", "Application.java");
    run(
        source,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "-m",
        "main");
    run(source, "git", "checkout", "-b", "feature/pr-12");
    Files.writeString(source.resolve("Application.java"), "class PullRequestHead {}\n");
    run(source, "git", "add", "Application.java");
    run(
        source,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "-m",
        "pr head");
    String admittedHead = output(source, "git", "rev-parse", "HEAD");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.resolve("runs").toString());
    WorkspaceInitService service =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            snapshots,
            mock(VendoredPathGuard.class),
            mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class),
            new WorkspaceRepositoryRevisionService(properties, snapshots));
    UUID repoId = UUID.randomUUID();
    when(snapshots.findByRunId("run-pr-clone")).thenReturn(List.of());

    service.cloneMultipleRepos(
        "run-pr-clone",
        List.of(
            new WorkspaceInitService.RepoToClone(
                "acme/app", source.toString(), repoId, "feature/pr-12")),
        null);

    Path clone = workspace.resolve("runs/run-pr-clone/repos/app");
    assertEquals("feature/pr-12", output(clone, "git", "branch", "--show-current"));
    assertTrue(Files.readString(clone.resolve("Application.java")).contains("PullRequestHead"));
    ArgumentCaptor<RunRepoSnapshot> snapshot = ArgumentCaptor.forClass(RunRepoSnapshot.class);
    verify(snapshots).save(snapshot.capture());
    assertEquals("feature/pr-12", snapshot.getValue().getBranch());
    assertEquals(repoId, snapshot.getValue().getRepoId());
    assertEquals(output(clone, "git", "rev-parse", "HEAD"), snapshot.getValue().getCommitSha());
    service.requirePublicationHead("run-pr-clone", "acme/app", admittedHead);

    run(clone, "git", "config", "user.name", "Test");
    run(clone, "git", "config", "user.email", "test@example.com");
    Files.writeString(clone.resolve("Application.java"), "class AdvancedHead {}\n");
    run(clone, "git", "add", "Application.java");
    run(clone, "git", "commit", "-m", "unexpected newer head");
    IllegalStateException stale =
        assertThrows(
            IllegalStateException.class,
            () -> service.requirePublicationHead("run-pr-clone", "acme/app", admittedHead));
    assertTrue(stale.getMessage().contains("head changed"));
  }

  @Test
  void snapshotPersistenceRecordsTheActualDefaultBranchInsteadOfAssumingMain() throws Exception {
    Path checkout = workspace.resolve("default-branch-checkout");
    Files.createDirectories(checkout);
    run(checkout, "git", "init", "-b", "trunk");
    Files.writeString(checkout.resolve("Application.java"), "class Application {}\n");
    run(checkout, "git", "add", "Application.java");
    run(
        checkout,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "-m",
        "baseline");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId("run-default")).thenReturn(List.of());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    WorkspaceRepositoryRevisionService revisions =
        new WorkspaceRepositoryRevisionService(properties, snapshots);

    revisions.persistCloneSnapshot(
        "run-default",
        new WorkspaceInitService.RepoToClone("acme/app", null, null, null),
        checkout);

    ArgumentCaptor<RunRepoSnapshot> snapshot = ArgumentCaptor.forClass(RunRepoSnapshot.class);
    verify(snapshots).save(snapshot.capture());
    assertEquals("trunk", snapshot.getValue().getBranch());
    assertEquals(output(checkout, "git", "rev-parse", "HEAD"), snapshot.getValue().getCommitSha());
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
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    WorkspaceInitService service =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            snapshots,
            mock(VendoredPathGuard.class),
            baseline,
            new WorkspaceRepositoryRevisionService(properties, snapshots));

    service.cloneReadOnlyRepository("project-source", source.toString(), null, null);

    assertTrue(Files.exists(service.runWorkspacePath("project-source").resolve("App.java")));
    verifyNoInteractions(snapshots, baseline);
  }

  @Test
  void emptyRepositoryBaselineIsRejectedWithoutFabricatingSnapshot() throws Exception {
    Path empty = workspace.resolve("empty");
    Files.createDirectories(empty);
    run(empty, "git", "init", "-b", "main");
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    WorkspaceRepositoryRevisionService revisions =
        new WorkspaceRepositoryRevisionService(properties, snapshots);

    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                revisions.persistCloneSnapshot(
                    "run-empty",
                    new WorkspaceInitService.RepoToClone(
                        "acme/empty", "https://github.com/acme/empty", null, "main"),
                    empty));

    assertTrue(failure.getMessage().contains("could not persist repository baseline"));
    assertTrue(failure.getCause().getMessage().contains("repository has no committed revision"));
    verifyNoInteractions(snapshots);
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
    assertEquals(0, process.waitFor(), new String(process.getErrorStream().readAllBytes()));
  }

  private static String output(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
    String stdout = new String(process.getInputStream().readAllBytes()).trim();
    assertEquals(0, process.waitFor(), new String(process.getErrorStream().readAllBytes()));
    return stdout;
  }

  private WorkspaceInitService service() {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    return new WorkspaceInitService(
        properties,
        mock(WorkspaceRestoreService.class),
        mock(RunRepoSnapshotRepository.class),
        mock(VendoredPathGuard.class),
        mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class),
        new WorkspaceRepositoryRevisionService(properties, mock(RunRepoSnapshotRepository.class)));
  }
}
