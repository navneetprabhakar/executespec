package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the database side of rolling-JVM recovery, rather than only mocking repository CAS
 * results. The two calls represent two boot scanners racing for the same stale run; exactly one may
 * advance the durable recovery attempt. The task CAS is then tested against the same stale row to
 * ensure a second scanner cannot re-take it.
 */
class CrossJvmRecoveryCasIntegrationTest extends PostgresIntegrationBase {

  private static final String RUN_ID = "cross-jvm-cas-run";
  private static final String TASK_ID = "cross-jvm-cas-task";

  @Autowired private PipelineRunRepository runRepository;
  @Autowired private AgentTaskRepository taskRepository;

  @AfterEach
  void cleanup() {
    taskRepository.deleteById(TASK_ID);
    runRepository.deleteById(RUN_ID);
  }

  @Test
  void twoRecoveryScannersOnlyOneClaimsTheRunAndTask() throws Exception {
    Instant staleHeartbeat = Instant.now().minus(Duration.ofMinutes(10));
    runRepository.saveAndFlush(
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("WORKING")
            .harnessVersion("v2")
            .runMode("BUILD")
            .resumeAttempts(0)
            .executionHeartbeat(staleHeartbeat)
            .tenantId("default")
            .build());
    taskRepository.saveAndFlush(
        AgentTask.builder()
            .taskId(TASK_ID)
            .runId(RUN_ID)
            .status("WORKING")
            .title("cross JVM recovery")
            .tenantId("default")
            .lastActivityAt(staleHeartbeat)
            .recoveryAttempt(0)
            .build());

    Instant now = Instant.now();
    Instant staleBefore = now.minus(Duration.ofMinutes(2));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first = pool.submit(() -> claimRun(ready, start, "jvm-a", now, staleBefore));
      Future<Integer> second = pool.submit(() -> claimRun(ready, start, "jvm-b", now, staleBefore));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Integer> claims =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(claims).containsExactlyInAnyOrder(0, 1);
    } finally {
      pool.shutdownNow();
    }

    assertThat(taskRepository.claimStaleForRecovery(TASK_ID, now, staleBefore)).isEqualTo(1);
    assertThat(taskRepository.claimStaleForRecovery(TASK_ID, now.plusSeconds(1), staleBefore))
        .isZero();
  }

  private int claimRun(
      CountDownLatch ready, CountDownLatch start, String instance, Instant now, Instant staleBefore)
      throws Exception {
    ready.countDown();
    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
    return runRepository.claimCrashResume(RUN_ID, 0, 1, now, staleBefore, instance);
  }
}
