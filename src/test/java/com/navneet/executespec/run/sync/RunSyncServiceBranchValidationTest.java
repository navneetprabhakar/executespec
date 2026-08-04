package com.navneet.executespec.run.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.navneet.executespec.deployment.WorkspaceArchiveUnavailableException;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1555 — {@code branch} reaches {@code git fetch origin <branch>} and {@code git reset --hard
 * origin/<branch>} as a raw argv token. Git parses options anywhere before {@code --}, so a value
 * beginning with {@code -} is consumed as a git option rather than a refspec.
 */
class RunSyncServiceBranchValidationTest {

  @Test
  void optionShapedBranchesAreRejected() {
    assertThat(RunSyncService.isValidBranch("--upload-pack=/bin/false")).isFalse();
    assertThat(RunSyncService.isValidBranch("--exec=touch /tmp/pwn")).isFalse();
    assertThat(RunSyncService.isValidBranch("-o")).isFalse();
    // Metacharacters and whitespace are refused too, so the token can never become more than a ref.
    assertThat(RunSyncService.isValidBranch("main;rm -rf /")).isFalse();
    assertThat(RunSyncService.isValidBranch("main branch")).isFalse();
    assertThat(RunSyncService.isValidBranch("main$(id)")).isFalse();
    assertThat(RunSyncService.isValidBranch("")).isFalse();
    assertThat(RunSyncService.isValidBranch(null)).isFalse();
    assertThat(RunSyncService.isValidBranch("feature//double")).isFalse();
    assertThat(RunSyncService.isValidBranch("feature/../main")).isFalse();
    assertThat(RunSyncService.isValidBranch("feature/work.lock")).isFalse();
    assertThat(RunSyncService.isValidBranch("feature/trailing/")).isFalse();
    assertThat(RunSyncService.isValidBranch("feature/trailing.")).isFalse();
    assertThat(RunSyncService.isValidBranch("feature/@{upstream}")).isFalse();
  }

  @Test
  void ordinaryBranchNamesRemainAccepted() {
    assertThat(RunSyncService.isValidBranch("main")).isTrue();
    assertThat(RunSyncService.isValidBranch("feature/RL-2026-07_fix-1.2")).isTrue();
    assertThat(RunSyncService.isValidBranch("executespec/abc1234")).isTrue();
  }

  /**
   * Defence in depth: even if a caller reached the service directly, an option-shaped branch must
   * never reach {@link ProcessBuilder}. The workspace deliberately does not exist — the validation
   * has to fire BEFORE the workspace lookup for that guarantee to hold.
   */
  @Test
  void theServiceRefusesAnOptionShapedBranchBeforeTouchingTheWorkspace(@TempDir Path tmp) {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunSyncService service =
        new RunSyncService(
            workspaceInit,
            mock(RunRepoSnapshotRepository.class),
            mock(ProjectRepoRepository.class),
            authorities,
            mock(WorkspaceRestoreService.class),
            mock(com.navneet.executespec.state.repository.PipelineRunRepository.class));
    when(workspaceInit.runWorkspacePath("run-1")).thenReturn(tmp.resolve("absent"));

    assertThatThrownBy(
            () ->
                service.sync(
                    "run-1", "--upload-pack=/bin/false", RunSyncService.GitCredential.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid branch");
    verify(authorities, never()).acquire(anyString(), any());
  }

  /**
   * RR-1554 — a snapshot row that is not a full 40-hex object id is not a reset target. Resetting
   * to an arbitrary string would let a malformed row reach git as an option or revision range, and
   * {@code git reset --hard} is destructive.
   */
  @Test
  void aMalformedSnapshotShaIsNeverResetTo(@TempDir Path tmp) throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease authority = mock(RunWorkspaceAuthorityService.Lease.class);
    Path runRoot = tmp.resolve("run-2");
    Files.createDirectories(runRoot.resolve("repos").resolve("app").resolve(".git"));
    when(workspaceInit.runWorkspacePath("run-2")).thenReturn(runRoot);

    var snap = new com.navneet.executespec.state.entity.RunRepoSnapshot();
    snap.setRunId("run-2");
    snap.setRepoFullName("acme/app");
    snap.setCommitSha("--exec=touch /tmp/pwn");
    when(snapshots.findByRunId("run-2")).thenReturn(java.util.List.of(snap));
    when(authorities.acquire("run-2", RunWorkspaceAuthorityService.Authority.PUBLICATION))
        .thenReturn(authority);

    RunSyncService service =
        new RunSyncService(
            workspaceInit,
            snapshots,
            mock(ProjectRepoRepository.class),
            authorities,
            mock(WorkspaceRestoreService.class),
            mock(com.navneet.executespec.state.repository.PipelineRunRepository.class));
    var outcome = service.syncToOriginalCommit("run-2", RunSyncService.GitCredential.NONE);

    assertThat(outcome.reposSynced()).isZero();
    assertThat(outcome.details()).singleElement().asString().contains("40-character object id");
    verify(authority).assertOwned();
    verify(authority).close();
  }

  @Test
  void restoredWorkspaceRebuildsRemoteAndCanSyncCurrentThenOriginal(@TempDir Path tmp)
      throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    ProjectRepoRepository projectRepos = mock(ProjectRepoRepository.class);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease authority = mock(RunWorkspaceAuthorityService.Lease.class);
    WorkspaceRestoreService restore = mock(WorkspaceRestoreService.class);
    Path restored = tmp.resolve("restored");
    Path restoredRepo = restored.resolve("repos/app");

    Path source = tmp.resolve("source");
    Files.createDirectories(source);
    git(source, "init", "-b", "main");
    git(source, "config", "user.email", "test@example.com");
    git(source, "config", "user.name", "Test");
    Files.writeString(source.resolve("app.txt"), "v1");
    git(source, "add", "app.txt");
    git(source, "commit", "-m", "v1");
    String originalSha = git(source, "rev-parse", "HEAD").trim();
    Files.writeString(source.resolve("app.txt"), "v2");
    git(source, "commit", "-am", "v2");
    String currentSha = git(source, "rev-parse", "HEAD").trim();
    Path remote = tmp.resolve("remote.git");
    git(tmp, "clone", "--bare", source.toString(), remote.toString());

    UUID repoId = UUID.randomUUID();
    RunRepoSnapshot snapshot =
        RunRepoSnapshot.builder()
            .runId("run-restored")
            .repoId(repoId)
            .repoFullName("acme/app")
            .branch("main")
            .commitSha(originalSha)
            .build();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider("GITHUB")
            .scmCloneUrl(remote.toString())
            .build();
    when(workspaceInit.runWorkspacePath("run-restored")).thenReturn(restored);
    when(restore.ensureWorkspaceAvailable("run-restored"))
        .thenAnswer(
            ignored -> {
              Files.createDirectories(restoredRepo);
              Files.writeString(restoredRepo.resolve("stale.txt"), "archive-only");
              return restored;
            });
    when(snapshots.findByRunId("run-restored")).thenReturn(java.util.List.of(snapshot));
    when(projectRepos.findById(repoId)).thenReturn(java.util.Optional.of(repo));
    when(authorities.acquire("run-restored", RunWorkspaceAuthorityService.Authority.PUBLICATION))
        .thenReturn(authority);
    RunSyncService service =
        new RunSyncService(
            workspaceInit,
            snapshots,
            projectRepos,
            authorities,
            restore,
            mock(com.navneet.executespec.state.repository.PipelineRunRepository.class));

    RunSyncService.SyncOutcome current =
        service.sync("run-restored", null, RunSyncService.GitCredential.NONE);
    assertThat(current.reposSynced()).isOne();
    assertThat(git(restoredRepo, "rev-parse", "HEAD").trim()).isEqualTo(currentSha);
    assertThat(Files.readString(restoredRepo.resolve("app.txt"))).isEqualTo("v2");
    assertThat(restoredRepo.resolve("stale.txt")).doesNotExist();

    RunSyncService.SyncOutcome original =
        service.syncToOriginalCommit("run-restored", RunSyncService.GitCredential.NONE);

    assertThat(original.reposSynced()).isOne();
    assertThat(git(restoredRepo, "rev-parse", "HEAD").trim()).isEqualTo(originalSha);
    assertThat(Files.readString(restoredRepo.resolve("app.txt"))).isEqualTo("v1");
    verify(restore).ensureWorkspaceAvailable("run-restored");
  }

  @Test
  void standaloneRestoreUsesTheRunsExactProviderNeutralCloneCoordinate() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-bitbucket");
    run.setRepoUrl("https://bitbucket.org/acme/app.git");
    when(runs.findById("run-bitbucket")).thenReturn(java.util.Optional.of(run));
    RunSyncService service =
        new RunSyncService(
            mock(WorkspaceInitService.class),
            mock(RunRepoSnapshotRepository.class),
            mock(ProjectRepoRepository.class),
            mock(RunWorkspaceAuthorityService.class),
            mock(WorkspaceRestoreService.class),
            runs);
    RunRepoSnapshot standalone =
        RunRepoSnapshot.builder()
            .runId("run-bitbucket")
            .repoFullName("acme/app")
            .branch("main")
            .commitSha("a".repeat(40))
            .build();

    assertThat(service.cloneUrl(standalone)).isEqualTo("https://bitbucket.org/acme/app.git");
  }

  @Test
  void standaloneRestoreRejectsCloneCoordinatesWithCredentialBearingUriComponents() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    RunSyncService service =
        new RunSyncService(
            mock(WorkspaceInitService.class),
            mock(RunRepoSnapshotRepository.class),
            mock(ProjectRepoRepository.class),
            mock(RunWorkspaceAuthorityService.class),
            mock(WorkspaceRestoreService.class),
            runs);
    for (String coordinate :
        java.util.List.of(
            "https://user:secret@bitbucket.org/acme/app.git",
            "https://bitbucket.org/acme/app.git?access_token=secret",
            "https://bitbucket.org/acme/app.git#token=secret")) {
      PipelineRun run = new PipelineRun();
      run.setRunId("run-credentialed");
      run.setRepoUrl(coordinate);
      when(runs.findById("run-credentialed")).thenReturn(java.util.Optional.of(run));
      RunRepoSnapshot standalone =
          RunRepoSnapshot.builder()
              .runId("run-credentialed")
              .repoFullName("acme/app")
              .branch("main")
              .commitSha("a".repeat(40))
              .build();

      assertThatThrownBy(() -> service.cloneUrl(standalone))
          .isInstanceOf(RunWorkspaceRestoreFailedException.class)
          .hasMessage("Workspace restore failed for run run-credentialed")
          .hasMessageNotContaining("secret");
    }
  }

  @Test
  void projectRestoreFailsClosedWithoutADurableRepositoryCoordinate() {
    RunSyncService service =
        new RunSyncService(
            mock(WorkspaceInitService.class),
            mock(RunRepoSnapshotRepository.class),
            mock(ProjectRepoRepository.class),
            mock(RunWorkspaceAuthorityService.class),
            mock(WorkspaceRestoreService.class),
            mock(PipelineRunRepository.class));
    RunRepoSnapshot project =
        RunRepoSnapshot.builder()
            .runId("run-project")
            .repoId(UUID.randomUUID())
            .repoFullName("acme/app")
            .branch("main")
            .commitSha("a".repeat(40))
            .build();

    assertThatThrownBy(() -> service.cloneUrl(project))
        .isInstanceOf(RunWorkspaceRestoreFailedException.class)
        .hasMessage("Workspace restore failed for run run-project");
  }

  @Test
  void standaloneSyncAndOriginalIgnoreNestedGitCollision(@TempDir Path tmp) throws Exception {
    Path source = tmp.resolve("source");
    Files.createDirectories(source);
    git(source, "init", "-b", "main");
    git(source, "config", "user.email", "test@example.com");
    git(source, "config", "user.name", "Test");
    Files.writeString(source.resolve("app.txt"), "v1");
    git(source, "add", "app.txt");
    git(source, "commit", "-m", "v1");
    String originalSha = git(source, "rev-parse", "HEAD").trim();
    Files.writeString(source.resolve("app.txt"), "v2");
    git(source, "commit", "-am", "v2");
    String currentSha = git(source, "rev-parse", "HEAD").trim();
    Path remote = tmp.resolve("remote.git");
    git(tmp, "clone", "--bare", source.toString(), remote.toString());

    Path runRoot = tmp.resolve("run-collision");
    git(tmp, "clone", remote.toString(), runRoot.toString());
    git(runRoot, "reset", "--hard", originalSha);
    Path nested = runRoot.resolve("repos/app");
    Files.createDirectories(nested);
    git(nested, "init", "-b", "main");
    git(nested, "config", "user.email", "test@example.com");
    git(nested, "config", "user.name", "Test");
    Files.writeString(nested.resolve("bait.txt"), "do-not-sync");
    git(nested, "add", "bait.txt");
    git(nested, "commit", "-m", "bait");
    String nestedSha = git(nested, "rev-parse", "HEAD").trim();

    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease authority = mock(RunWorkspaceAuthorityService.Lease.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    RunRepoSnapshot snapshot =
        RunRepoSnapshot.builder()
            .runId("run-collision")
            .repoFullName("acme/app")
            .branch("main")
            .commitSha(originalSha)
            .build();
    PipelineRun run =
        PipelineRun.builder().runId("run-collision").repoUrl(remote.toString()).build();
    when(workspaceInit.runWorkspacePath("run-collision")).thenReturn(runRoot);
    when(snapshots.findByRunId("run-collision")).thenReturn(java.util.List.of(snapshot));
    when(runs.findById("run-collision")).thenReturn(java.util.Optional.of(run));
    when(authorities.acquire("run-collision", RunWorkspaceAuthorityService.Authority.PUBLICATION))
        .thenReturn(authority);
    RunSyncService service =
        new RunSyncService(
            workspaceInit,
            snapshots,
            mock(ProjectRepoRepository.class),
            authorities,
            mock(WorkspaceRestoreService.class),
            runs);

    assertThat(service.sync("run-collision", null, RunSyncService.GitCredential.NONE).reposSynced())
        .isOne();
    assertThat(git(runRoot, "rev-parse", "HEAD").trim()).isEqualTo(currentSha);
    assertThat(git(nested, "rev-parse", "HEAD").trim()).isEqualTo(nestedSha);

    assertThat(
            service
                .syncToOriginalCommit("run-collision", RunSyncService.GitCredential.NONE)
                .reposSynced())
        .isOne();
    assertThat(git(runRoot, "rev-parse", "HEAD").trim()).isEqualTo(originalSha);
    assertThat(git(nested, "rev-parse", "HEAD").trim()).isEqualTo(nestedSha);
  }

  @Test
  void permanentlyMissingWorkspaceHasStableDomainFailure(@TempDir Path tmp) {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease authority = mock(RunWorkspaceAuthorityService.Lease.class);
    WorkspaceRestoreService restore = mock(WorkspaceRestoreService.class);
    when(workspaceInit.runWorkspacePath("run-gone")).thenReturn(tmp.resolve("gone"));
    when(restore.ensureWorkspaceAvailable("run-gone"))
        .thenThrow(new WorkspaceArchiveUnavailableException("run-gone"));
    when(authorities.acquire("run-gone", RunWorkspaceAuthorityService.Authority.PUBLICATION))
        .thenReturn(authority);
    RunSyncService service =
        new RunSyncService(
            workspaceInit,
            mock(RunRepoSnapshotRepository.class),
            mock(ProjectRepoRepository.class),
            authorities,
            restore,
            mock(com.navneet.executespec.state.repository.PipelineRunRepository.class));

    assertThatThrownBy(() -> service.sync("run-gone", null, RunSyncService.GitCredential.NONE))
        .isInstanceOf(RunWorkspaceUnavailableException.class)
        .hasMessage("Workspace is unavailable for run run-gone")
        .hasMessageNotContaining("archive key")
        .hasMessageNotContaining("internal path");
    verify(authority).close();
  }

  @Test
  void transientRestoreFaultIsRetryablePlatformFailureNotPermanentGone(@TempDir Path tmp) {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease authority = mock(RunWorkspaceAuthorityService.Lease.class);
    WorkspaceRestoreService restore = mock(WorkspaceRestoreService.class);
    when(workspaceInit.runWorkspacePath("run-retry")).thenReturn(tmp.resolve("gone"));
    when(restore.ensureWorkspaceAvailable("run-retry"))
        .thenThrow(new RuntimeException("temporary GCS path and internal host"));
    when(authorities.acquire("run-retry", RunWorkspaceAuthorityService.Authority.PUBLICATION))
        .thenReturn(authority);
    RunSyncService service =
        new RunSyncService(
            workspaceInit,
            mock(RunRepoSnapshotRepository.class),
            mock(ProjectRepoRepository.class),
            authorities,
            restore,
            mock(com.navneet.executespec.state.repository.PipelineRunRepository.class));

    assertThatThrownBy(() -> service.sync("run-retry", null, RunSyncService.GitCredential.NONE))
        .isInstanceOf(RunWorkspaceRestoreFailedException.class)
        .hasMessage("Workspace restore failed for run run-retry")
        .hasMessageNotContaining("GCS path")
        .hasMessageNotContaining("internal host");
    verify(authority).close();
  }

  @Test
  void gitCredentialFactoriesUseTheProviderSpecificGitUsername() {
    assertThat(RunSyncService.GitCredential.github("pat").username()).isNull();
    assertThat(RunSyncService.GitCredential.github("pat").secret()).isEqualTo("pat");
    assertThat(RunSyncService.GitCredential.bitbucket("tok").username())
        .isEqualTo("x-bitbucket-api-token-auth");
    // Blank credentials collapse to NONE rather than producing an empty credential helper.
    assertThat(RunSyncService.GitCredential.github("  "))
        .isEqualTo(RunSyncService.GitCredential.NONE);
    assertThat(RunSyncService.GitCredential.bitbucket(null))
        .isEqualTo(RunSyncService.GitCredential.NONE);
  }

  private static String git(Path directory, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(java.util.List.of(args));
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).as(output).isZero();
    return output;
  }
}
