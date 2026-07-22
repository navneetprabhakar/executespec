package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-034 (2026-07-22, P1) — a bounded Git invocation must be killed when it exceeds its wall-clock
 * budget rather than pinning the caller indefinitely.
 */
class WorkspaceGitCommitServiceTest {

  private final WorkspaceGitCommitService service = new WorkspaceGitCommitService();

  @Test
  void aProcessThatExceedsTheTimeoutIsKilledAndReportedAsNotCompleted(@TempDir Path dir)
      throws Exception {
    long start = System.currentTimeMillis();
    WorkspaceGitCommitService.GitProcessResult result =
        service.runBoundedGit(dir, 1, "sh", "-c", "sleep 30");
    long elapsed = System.currentTimeMillis() - start;

    assertThat(result.completed()).isFalse();
    // Bounded to ~1s (plus the 5s reap grace), never the full 30s the child would have slept.
    assertThat(elapsed).isLessThan(20_000);
  }

  @Test
  void aFastCommandCompletesWithItsExitCode(@TempDir Path dir) throws Exception {
    WorkspaceGitCommitService.GitProcessResult ok =
        service.runBoundedGit(dir, 10, "sh", "-c", "exit 0");
    assertThat(ok.completed()).isTrue();
    assertThat(ok.exitCode()).isZero();

    WorkspaceGitCommitService.GitProcessResult bad =
        service.runBoundedGit(dir, 10, "sh", "-c", "exit 3");
    assertThat(bad.completed()).isTrue();
    assertThat(bad.exitCode()).isEqualTo(3);
  }
}
