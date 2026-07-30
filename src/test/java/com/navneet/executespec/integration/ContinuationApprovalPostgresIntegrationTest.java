package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Real PostgreSQL coverage for the exact continuation-approval CAS and lifecycle fence. */
class ContinuationApprovalPostgresIntegrationTest extends PostgresIntegrationBase {

  private static final String PREFIX = "continuation-cas-";
  private static final String RUN_ID = PREFIX + "run";
  private static final String TASK_ID = PREFIX + "task";
  private static final String PROPOSAL =
      "{\"decision\":\"STOP_BLOCKED\",\"selectedCandidate\":{\"provider\":\"openrouter\","
          + "\"model\":\"x-ai/grok-4.20\"}}";
  private static final String DIGEST = "a".repeat(64);

  @Autowired AgentTaskRepository tasks;

  @BeforeEach
  void setUp() {
    tasks.deleteAll(tasks.findByRunId(RUN_ID));
    tasks.saveAndFlush(
        AgentTask.builder()
            .taskId(TASK_ID)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(PROPOSAL)
            .build());
  }

  @Test
  void concurrentSameProposalHasOneWinnerAndTerminalReceiptAllowsReplacement() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> claims =
          List.of(
              executor.submit(
                  () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return claim(UUID.randomUUID().toString(), DIGEST, PROPOSAL);
                  }),
              executor.submit(
                  () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return claim(UUID.randomUUID().toString(), DIGEST, PROPOSAL);
                  }));
      start.countDown();
      assertThat(claims.get(0).get(10, TimeUnit.SECONDS) + claims.get(1).get(10, TimeUnit.SECONDS))
          .isEqualTo(1);
    }

    AgentTask winner = tasks.findById(TASK_ID).orElseThrow();
    assertThat(winner.getContinuationApprovalStatus()).isEqualTo("CLAIMED");
    assertThat(winner.getContinuationApprovalUpdatedAt()).isNotNull();
    assertThat(claim("different-active", "b".repeat(64), PROPOSAL)).isZero();

    assertThat(
            tasks.updateContinuationApprovalStatus(
                TASK_ID,
                winner.getContinuationApprovalId(),
                winner.getContinuationApprovalProposalDigest(),
                "QUEUED"))
        .isEqualTo(1);
    assertThat(
            tasks.updateContinuationApprovalStatus(
                TASK_ID,
                winner.getContinuationApprovalId(),
                winner.getContinuationApprovalProposalDigest(),
                "COMPLETED"))
        .isEqualTo(1);
    assertThat(claim("replacement", "b".repeat(64), PROPOSAL)).isEqualTo(1);
  }

  private int claim(String approvalId, String digest, String proposal) {
    return tasks.claimContinuationApproval(
        TASK_ID,
        RUN_ID,
        proposal,
        digest,
        approvalId,
        "openrouter",
        "x-ai/grok-4.20",
        "CHANGE_PROVIDER",
        false);
  }
}
