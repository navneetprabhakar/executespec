package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.harness.WorkspaceInitService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CodeReviewServiceCleanupAndCostTest {

  @TempDir Path tempRoot;

  private final WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
  private final RunCostTracker costTracker = mock(RunCostTracker.class);

  private final CodeReviewService service =
      new CodeReviewService(
          mock(com.navneet.executespec.codereview.repository.CodeReviewScanRepository.class),
          mock(com.navneet.executespec.codereview.repository.CodeReviewIssueRepository.class),
          mock(SemgrepRunner.class),
          mock(CodeReviewAgent.class),
          workspaceInit,
          costTracker,
          mock(com.navneet.executespec.sse.SseBroadcaster.class),
          mock(com.navneet.executespec.project.repository.ProjectRepoRepository.class),
          mock(com.navneet.executespec.repo.GitHubRepoService.class),
          mock(com.navneet.executespec.auth.repository.TierConfigRepository.class),
          mock(com.navneet.executespec.router.ProjectProviderResolver.class));

  @AfterEach
  void clearTx() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // RR-439 — the review clone is reclaimed no-follow on every terminal path.
  @Test
  void cleanupDeletesTheReviewClone() throws Exception {
    Path clone = Files.createDirectories(tempRoot.resolve("scan-abc"));
    Files.writeString(clone.resolve("src.java"), "class A {}\n");
    Files.createDirectories(clone.resolve("nested"));
    Files.writeString(clone.resolve("nested/b.txt"), "x");
    when(workspaceInit.runWorkspacePath("scan-abc")).thenReturn(clone);

    service.cleanupReviewWorkspace("scan-abc");

    assertThat(Files.exists(clone)).isFalse();
  }

  @Test
  void cleanupIgnoresNonScanWorkspaceNames() {
    // Defence: a mis-derived id that is not a review clone must never be deleted.
    service.cleanupReviewWorkspace("run-42");

    verify(workspaceInit, never()).runWorkspacePath("run-42");
  }

  // RR-440 — the cost bucket is freed only AFTER the surrounding transaction commits, so a rollback
  // leaves it intact for the retry to re-snapshot.
  @Test
  void costBucketIsFreedOnlyAfterCommitWhenATransactionIsActive() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.finalizeCostBucketAfterCommit("code-review-1");

      // Deferred: nothing freed yet.
      verify(costTracker, never()).finalizeRun("code-review-1");

      // Simulate commit.
      for (TransactionSynchronization sync :
          TransactionSynchronizationManager.getSynchronizations()) {
        sync.afterCommit();
      }
      verify(costTracker).finalizeRun("code-review-1");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void costBucketIsFreedImmediatelyWhenNoTransactionIsActive() {
    service.finalizeCostBucketAfterCommit("code-review-2");

    verify(costTracker).finalizeRun("code-review-2");
  }
}
