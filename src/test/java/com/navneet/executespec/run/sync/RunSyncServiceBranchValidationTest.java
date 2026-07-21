package com.navneet.executespec.run.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.harness.WorkspaceInitService;
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
    RunSyncService service =
        new RunSyncService(workspaceInit, mock(RunRepoSnapshotRepository.class));
    when(workspaceInit.runWorkspacePath("run-1")).thenReturn(tmp.resolve("absent"));

    assertThatThrownBy(
            () ->
                service.sync(
                    "run-1", "--upload-pack=/bin/false", RunSyncService.GitCredential.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid branch");
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
    Path runRoot = tmp.resolve("run-2");
    Files.createDirectories(runRoot.resolve("repos").resolve("app").resolve(".git"));
    when(workspaceInit.runWorkspacePath("run-2")).thenReturn(runRoot);

    var snap = new com.navneet.executespec.state.entity.RunRepoSnapshot();
    snap.setRunId("run-2");
    snap.setRepoFullName("acme/app");
    snap.setCommitSha("--exec=touch /tmp/pwn");
    when(snapshots.findByRunId("run-2")).thenReturn(java.util.List.of(snap));

    RunSyncService service = new RunSyncService(workspaceInit, snapshots);
    var outcome = service.syncToOriginalCommit("run-2", RunSyncService.GitCredential.NONE);

    assertThat(outcome.reposSynced()).isZero();
    assertThat(outcome.details()).singleElement().asString().contains("40-character object id");
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
}
