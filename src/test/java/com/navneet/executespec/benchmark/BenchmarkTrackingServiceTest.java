package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.RunStepUsage;
import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class BenchmarkTrackingServiceTest {

  private final BenchmarkRunRepository runs = mock(BenchmarkRunRepository.class);
  private final BenchmarkTaskResultRepository taskResults =
      mock(BenchmarkTaskResultRepository.class);
  private final BenchmarkActiveAttemptRepository activeAttempts =
      mock(BenchmarkActiveAttemptRepository.class);
  private final BenchmarkEventRepository events = mock(BenchmarkEventRepository.class);
  private final SseBroadcaster sse = mock(SseBroadcaster.class);
  private final RunStepUsageRepository usage = mock(RunStepUsageRepository.class);
  private final Map<String, BenchmarkRun> runStore = new HashMap<>();
  private final List<BenchmarkTaskResult> taskStore = new ArrayList<>();
  private final List<BenchmarkEvent> eventStore = new ArrayList<>();
  private final List<BenchmarkActiveAttempt> activeStore = new ArrayList<>();

  private BenchmarkTrackingService service;

  @BeforeEach
  void setUp() {
    service =
        new BenchmarkTrackingService(
            runs, taskResults, activeAttempts, events, sse, new ObjectMapper(), usage);

    doAnswer(
            inv -> {
              BenchmarkRun run = inv.getArgument(0);
              runStore.put(run.getId(), run);
              return run;
            })
        .when(runs)
        .save(any(BenchmarkRun.class));
    when(runs.findById(any(String.class)))
        .thenAnswer(inv -> Optional.ofNullable(runStore.get(inv.getArgument(0))));
    when(runs.findByIdForUpdate(any(String.class)))
        .thenAnswer(inv -> Optional.ofNullable(runStore.get(inv.getArgument(0))));

    doAnswer(
            inv -> {
              BenchmarkTaskResult result = inv.getArgument(0);
              taskStore.removeIf(existing -> existing.getId().equals(result.getId()));
              taskStore.add(result);
              return result;
            })
        .when(taskResults)
        .save(any(BenchmarkTaskResult.class));
    when(taskResults.findByBenchmarkRunIdOrderByTaskNameAscAttemptAsc(any(String.class)))
        .thenAnswer(
            inv -> {
              String runId = inv.getArgument(0);
              return taskStore.stream()
                  .filter(t -> runId.equals(t.getBenchmarkRunId()))
                  .sorted(
                      Comparator.comparing(BenchmarkTaskResult::getTaskName)
                          .thenComparingInt(BenchmarkTaskResult::getAttempt))
                  .toList();
            });

    doAnswer(
            inv -> {
              BenchmarkActiveAttempt active = inv.getArgument(0);
              activeStore.removeIf(existing -> existing.getId().equals(active.getId()));
              activeStore.add(active);
              return active;
            })
        .when(activeAttempts)
        .save(any(BenchmarkActiveAttempt.class));
    when(activeAttempts.findByBenchmarkRunIdAndTaskNameAndAttempt(
            any(String.class), any(String.class), any(Integer.class)))
        .thenAnswer(
            inv ->
                activeStore.stream()
                    .filter(
                        a ->
                            a.getBenchmarkRunId().equals(inv.getArgument(0))
                                && a.getTaskName().equals(inv.getArgument(1))
                                && a.getAttempt() == (int) inv.getArgument(2))
                    .findFirst());
    when(activeAttempts.findByBenchmarkRunIdOrderByStartedAtAsc(any(String.class)))
        .thenAnswer(
            inv ->
                activeStore.stream()
                    .filter(a -> a.getBenchmarkRunId().equals(inv.getArgument(0)))
                    .sorted(Comparator.comparing(BenchmarkActiveAttempt::getStartedAt))
                    .toList());
    when(activeAttempts.countByBenchmarkRunId(any(String.class)))
        .thenAnswer(
            inv ->
                activeStore.stream()
                    .filter(a -> a.getBenchmarkRunId().equals(inv.getArgument(0)))
                    .count());
    doAnswer(
            inv -> {
              activeStore.removeIf(
                  a ->
                      a.getBenchmarkRunId().equals(inv.getArgument(0))
                          && a.getTaskName().equals(inv.getArgument(1))
                          && a.getAttempt() == (int) inv.getArgument(2));
              return null;
            })
        .when(activeAttempts)
        .deleteByBenchmarkRunIdAndTaskNameAndAttempt(
            any(String.class), any(String.class), any(Integer.class));
    doAnswer(
            inv -> {
              activeStore.removeIf(a -> a.getBenchmarkRunId().equals(inv.getArgument(0)));
              return null;
            })
        .when(activeAttempts)
        .deleteByBenchmarkRunId(any(String.class));

    doAnswer(
            inv -> {
              BenchmarkEvent event = inv.getArgument(0);
              eventStore.add(event);
              return event;
            })
        .when(events)
        .save(any(BenchmarkEvent.class));
    when(events.countByBenchmarkRunId(any(String.class)))
        .thenAnswer(
            inv -> {
              String runId = inv.getArgument(0);
              return eventStore.stream().filter(e -> runId.equals(e.getBenchmarkRunId())).count();
            });
  }

  @Test
  void persistsConcurrentActiveAttemptsAndReloadsThemAfterServiceRecreation() {
    BenchmarkRun run =
        service.start("SWE_BENCH", "lite-10", "openrouter", "glm-5.2", 1, 10, null, "u1", false);

    service.ingest(
        run.getId(),
        "TASK_STARTED",
        "django__django-1",
        1,
        null,
        null,
        null,
        null,
        null,
        "started",
        null,
        null,
        null,
        null,
        null,
        null,
        120L);
    BenchmarkRun afterSecond =
        service.ingest(
            run.getId(),
            "TASK_STARTED",
            "django__django-2",
            1,
            null,
            null,
            null,
            null,
            null,
            "started",
            null,
            null,
            null,
            null,
            null,
            null,
            90L);

    assertThat(afterSecond.getActiveAttemptCount()).isEqualTo(2);
    assertThat(service.activeAttempts(run.getId()))
        .extracting(BenchmarkActiveAttempt::getTaskName)
        .containsExactly("django__django-1", "django__django-2");

    BenchmarkTrackingService afterRestart =
        new BenchmarkTrackingService(
            runs, taskResults, activeAttempts, events, sse, new ObjectMapper(), usage);
    assertThat(afterRestart.get(run.getId()).getStartedAt()).isEqualTo(run.getStartedAt());
    assertThat(afterRestart.activeAttempts(run.getId())).hasSize(2);
    activeStore.getFirst().setStaleAfter(Instant.EPOCH);
    assertThat(afterRestart.activeAttempts(run.getId()).getFirst().isStale()).isTrue();
  }

  @Test
  void exposesProgressProjectionInSseAndMakesSubmissionReadinessDeterministic() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "lite-one", "deepseek", "deepseek-v4-pro", 1, 1, null, "u1", true);
    String sha = "a".repeat(64);
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        0,
        "es-1",
        new BigDecimal("0.000100"),
        10L,
        1L,
        "timed out",
        null,
        "TIMEOUT",
        "agent timed out",
        null,
        "gs://bucket/run/task.json",
        sha,
        0L);
    BenchmarkRun done =
        service.ingest(
            run.getId(), "RUN_DONE", null, null, null, null, null, null, null, null, null);

    assertThat(done.getSubmissionReadiness()).isEqualTo("READY");
    assertThat(done.getCompletedAttempts()).isEqualTo(1);
    assertThat(taskStore.getFirst().getFailureClassification()).isEqualTo("TIMEOUT");
    assertThat(taskStore.getFirst().getTrajectorySha256()).isEqualTo(sha);
    var eventCaptor = org.mockito.ArgumentCaptor.forClass(RunEvent.class);
    verify(sse, org.mockito.Mockito.atLeastOnce()).emit(any(String.class), eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .anySatisfy(
            event -> {
              assertThat(event.payload()).contains("\"completedAttempts\":1");
              assertThat(event.payload()).contains("\"submissionReadiness\":\"READY\"");
            });
  }

  @Test
  void blocksSubmissionWhenTerminalTrajectoryEvidenceIsMissing() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "lite-one", "deepseek", "deepseek-v4-pro", 1, 1, null, "u1", true);
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        1,
        "es-1",
        BigDecimal.ZERO,
        1L,
        1L,
        null,
        null);
    BenchmarkRun done =
        service.ingest(
            run.getId(), "RUN_DONE", null, null, null, null, null, null, null, null, null);

    assertThat(done.getSubmissionReadiness()).isEqualTo("BLOCKED");
    assertThat(done.getSubmissionReadinessReason()).contains("lack trajectory");
  }

  @Test
  void acceptsTheDurableNdjsonTrajectoryFormatOnProgress() {
    BenchmarkRun run =
        service.start("SWE_BENCH", "lite-one", "openrouter", "glm-5.2", 1, 10, null, "u1", true);
    String sha = "a".repeat(64);

    service.ingest(
        run.getId(),
        "RUN_PROGRESS",
        "astropy__astropy-12907",
        1,
        null,
        "es-1",
        null,
        null,
        null,
        "provider-free capture recovered",
        null,
        null,
        null,
        null,
        "pass1.trajectories/c5278309.ndjson",
        sha,
        null);

    assertThat(service.activeAttempts(run.getId()))
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.getTrajectoryLocation())
                  .isEqualTo("pass1.trajectories/c5278309.ndjson");
              assertThat(attempt.getTrajectorySha256()).isEqualTo(sha);
            });
  }

  @Test
  void rejectsTrajectoryLocationsThatCouldPersistCredentials() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "lite-one", "deepseek", "deepseek-v4-pro", 1, 1, null, "u1", true);

    for (String unsafeLocation :
        List.of(
            "https://storage.example/trajectory?token=plaintext",
            "https://user:pass@storage.example/trajectory",
            "s3://user:pass@bucket/trajectory.json",
            "gs://credential@bucket/trajectory.json",
            "/tmp/trajectory.json",
            "../trajectory.json",
            "raw\ntrajectory")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  service.ingest(
                      run.getId(),
                      "TASK_SCORED",
                      "django__django-1",
                      1,
                      1,
                      "es-1",
                      BigDecimal.ZERO,
                      1L,
                      1L,
                      null,
                      null,
                      null,
                      null,
                      null,
                      unsafeLocation,
                      "a".repeat(64),
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("secret-free path");
    }
  }

  @Test
  void reconnectForwardsTheLastEventIdToTheReplayCapableBroadcaster() {
    SseEmitter emitter = mock(SseEmitter.class);
    when(sse.subscribe("benchmark-1", "17")).thenReturn(emitter);

    assertThat(service.subscribe("benchmark-1", "17")).isSameAs(emitter);
    verify(sse).subscribe("benchmark-1", "17");
  }

  @Test
  void reconcilesTrackedUsageFromCanonicalRunStepLedgerWithoutChangingScore() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "swebench-lite", "deepseek", "deepseek-v4-pro-native", 1, 1, null, "u1");
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        1,
        "es-1",
        new BigDecimal("0.010000"),
        0L,
        0L,
        null,
        null);
    when(usage.findByRunIdOrderByCreatedAtAsc("es-1"))
        .thenReturn(
            List.of(
                RunStepUsage.builder().inputTokens(120).outputTokens(8).costUsd(0.0123456).build(),
                RunStepUsage.builder().inputTokens(80).outputTokens(4).costUsd(0.0012345).build()));

    BenchmarkTrackingService.UsageReconciliation reconciled = service.reconcileUsage(run.getId());

    assertThat(reconciled.updatedTasks()).isEqualTo(1);
    assertThat(reconciled.providerCalls()).isEqualTo(2);
    assertThat(reconciled.run().getResolvedTasks()).isEqualTo(1);
    assertThat(reconciled.run().getInputTokens()).isEqualTo(200);
    assertThat(reconciled.run().getOutputTokens()).isEqualTo(12);
    assertThat(reconciled.run().getCostUsd()).isEqualByComparingTo("0.013580");
    assertThat(taskStore).hasSize(1);
    assertThat(taskStore.getFirst().getReward()).isEqualTo(1);
    assertThat(eventStore).extracting(BenchmarkEvent::getType).contains("USAGE_RECONCILED");

    BenchmarkTrackingService.UsageReconciliation replay = service.reconcileUsage(run.getId());
    assertThat(replay.updatedTasks()).isZero();
    assertThat(eventStore).extracting(BenchmarkEvent::getType).containsOnlyOnce("USAGE_RECONCILED");
  }

  /**
   * C1 Step 2 — when several physical providers served one attempt, the attempt must be recorded as
   * MIXED. Silently picking one of them is exactly the failure mode that lets a campaign attribute
   * work to a provider that did not do it.
   */
  @Test
  void reconcileMarksAMultiProviderAttemptAsMixedInsteadOfPickingOneProvider() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "swebench-lite", "deepseek", "deepseek-v4-pro-native", 1, 1, null, "u1");
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        1,
        "es-1",
        new BigDecimal("0.010000"),
        0L,
        0L,
        null,
        null);
    when(usage.findByRunIdOrderByCreatedAtAsc("es-1"))
        .thenReturn(
            List.of(
                usageRow("deepseek", "deepseek-v4-pro-native", 120, 8, 0.0123456),
                usageRow("openrouter", "glm-5.2", 80, 4, 0.0012345)));

    service.reconcileUsage(run.getId());

    BenchmarkTaskResult stored = taskStore.getFirst();
    assertThat(stored.getRouteAttribution()).isEqualTo("MIXED");
    assertThat(stored.getProvider()).isNull();
    assertThat(stored.getModel()).isNull();
    // Verifier evidence stays immutable.
    assertThat(stored.getReward()).isEqualTo(1);
    assertThat(stored.getEsRunId()).isEqualTo("es-1");
  }

  @Test
  void reconcileRecordsTheServedRouteWhenItDivergesFromTheRequestedOne() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "swebench-lite", "deepseek", "deepseek-v4-pro-native", 1, 1, null, "u1");
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        1,
        "es-1",
        BigDecimal.ZERO,
        0L,
        0L,
        null,
        null);
    when(usage.findByRunIdOrderByCreatedAtAsc("es-1"))
        .thenReturn(List.of(usageRow("openrouter", "glm-5.2", 100, 10, 0.02)));

    service.reconcileUsage(run.getId());

    BenchmarkTaskResult stored = taskStore.getFirst();
    assertThat(stored.getRouteAttribution()).isEqualTo("LEDGER");
    assertThat(stored.getProvider()).isEqualTo("openrouter");
    assertThat(stored.getModel()).isEqualTo("glm-5.2");
    // The parent run still carries the REQUESTED route; the divergence is visible per attempt.
    assertThat(run.getProvider()).isEqualTo("deepseek");
  }

  /** Fresh campaigns must be truthful at ingest, without needing a later repair pass. */
  @Test
  void taskScoredIngestDerivesTheRouteServerSideFromTheLinkedRun() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "swebench-lite", "deepseek", "deepseek-v4-pro-native", 1, 1, null, "u1");
    when(usage.findByRunIdOrderByCreatedAtAsc("es-1"))
        .thenReturn(List.of(usageRow("openrouter", "glm-5.2", 100, 10, 0.02)));

    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        1,
        "es-1",
        BigDecimal.ZERO,
        0L,
        0L,
        null,
        null);

    BenchmarkTaskResult stored = taskStore.getFirst();
    assertThat(stored.getRouteAttribution()).isEqualTo("LEDGER");
    assertThat(stored.getProvider()).isEqualTo("openrouter");
  }

  @Test
  void attemptWithNoLinkedRunStaysUnattributedRatherThanInheritingTheRunProvider() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "swebench-lite", "deepseek", "deepseek-v4-pro-native", 1, 1, null, "u1");

    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        0,
        null,
        BigDecimal.ZERO,
        0L,
        0L,
        null,
        null);

    BenchmarkTaskResult stored = taskStore.getFirst();
    assertThat(stored.getRouteAttribution()).isEqualTo("UNATTRIBUTED");
    assertThat(stored.getProvider()).isNull();
  }

  private static RunStepUsage usageRow(
      String provider, String model, long input, long output, double cost) {
    return RunStepUsage.builder()
        .provider(provider)
        .model(model)
        .attemptStatus("SUCCESS")
        .inputTokens(input)
        .outputTokens(output)
        .costUsd(cost)
        .build();
  }

  @Test
  void taskScoredIsIdempotentByTaskAndAttemptAndAggregatesCostAndTokens() {
    BenchmarkRun run =
        service.start(
            "TERMINAL_BENCH",
            "terminal-bench-2-1",
            "deepseek",
            "deepseek-v4-pro-native",
            1,
            89,
            "tb21-internal-deepseek-v4-pro-native-k1",
            "u1");

    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "task-a",
        1,
        0,
        "es-1",
        new BigDecimal("0.100000"),
        10L,
        5L,
        null,
        null);
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "task-a",
        1,
        0,
        "es-1-duplicate",
        new BigDecimal("99.000000"),
        999L,
        999L,
        null,
        null);
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "task-a",
        2,
        1,
        "es-2",
        new BigDecimal("0.200000"),
        20L,
        10L,
        null,
        null);
    BenchmarkRun afterTaskB =
        service.ingest(
            run.getId(),
            "TASK_SCORED",
            "task-b",
            1,
            1,
            "es-3",
            new BigDecimal("0.300000"),
            30L,
            15L,
            null,
            null);

    assertThat(taskStore).hasSize(3);
    assertThat(afterTaskB.getResolvedTasks()).isEqualTo(2);
    assertThat(afterTaskB.getCostUsd()).isEqualByComparingTo("0.600000");
    assertThat(afterTaskB.getInputTokens()).isEqualTo(60);
    assertThat(afterTaskB.getOutputTokens()).isEqualTo(30);

    BenchmarkRun done =
        service.ingest(
            run.getId(), "RUN_DONE", null, null, null, null, null, null, null, null, null);

    assertThat(done.getStatus()).isEqualTo("DONE");
    assertThat(done.getFinishedAt()).isNotNull();
    assertThat(eventStore).extracting(BenchmarkEvent::getType).contains("RUN_STARTED", "RUN_DONE");
  }

  @Test
  void submittedEventExtractsLeaderboardUrlFromPayload() {
    BenchmarkRun run =
        service.start(
            "TERMINAL_BENCH",
            "terminal-bench-2-1",
            "anthropic",
            "claude-sonnet-5",
            1,
            89,
            null,
            "u1");

    BenchmarkRun submitted =
        service.ingest(
            run.getId(),
            "SUBMITTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "{\"url\":\"https://example.com/result\"}");

    assertThat(submitted.getSubmissionStatus()).isEqualTo("SUBMITTED");
    assertThat(submitted.getLeaderboardUrl()).isEqualTo("https://example.com/result");
  }

  @Test
  void taskScoredPersistsOutcomeAndReasonWithoutChangingRewardMath() {
    BenchmarkRun run =
        service.start(
            "TERMINAL_BENCH",
            "terminal-bench-2-1",
            "deepseek",
            "deepseek-v4-flash-native",
            1,
            89,
            null,
            "u1");

    BenchmarkRun after =
        service.ingest(
            run.getId(),
            "TASK_SCORED",
            "task-infra",
            1,
            0,
            "es-infra",
            new BigDecimal("0.010000"),
            7L,
            3L,
            null,
            null,
            "TIMEOUT",
            "container timed out before verifier completed");

    assertThat(after.getResolvedTasks()).isZero();
    assertThat(taskStore).hasSize(1);
    assertThat(taskStore.get(0).getOutcome()).isEqualTo("TIMEOUT");
    assertThat(taskStore.get(0).getReason()).contains("container timed out");
  }

  // RR-1355 — an out-of-band reward is clamped to the binary resolved flag {0,1}; a negative cost
  // is
  // rejected outright so a faulty/compromised harness cannot poison headline metrics.
  @Test
  void rewardIsClampedToBinaryAndNegativeCostIsRejected() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "swebench-lite", "deepseek", "deepseek-v4-pro-native", 1, 2, null, "u1");
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "django__django-1",
        1,
        7, // impossible reward > 1
        "es-1",
        new BigDecimal("0.010000"),
        0L,
        0L,
        null,
        null);
    assertThat(taskStore.getFirst().getReward()).isEqualTo(1);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.ingest(
                    run.getId(),
                    "TASK_SCORED",
                    "django__django-2",
                    1,
                    1,
                    "es-2",
                    new BigDecimal("-5.000000"),
                    0L,
                    0L,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // RR-1595 — reconcile now separately reports attempts that carry no esRunId versus attempts
  // linked
  // to a run with no canonical usage rows, so "nothing to fix" is distinguishable from "nothing
  // could be fixed because attempts were never linked".
  @Test
  void reconcileCountsUnlinkedAndNoLedgerAttemptsSeparately() {
    BenchmarkRun run =
        service.start(
            "SWE_BENCH", "swebench-lite", "deepseek", "deepseek-v4-pro-native", 1, 2, null, "u1");
    service.ingest(
        run.getId(), "TASK_SCORED", "t-unlinked", 1, 1, null, BigDecimal.ZERO, 0L, 0L, null, null);
    service.ingest(
        run.getId(),
        "TASK_SCORED",
        "t-noledger",
        1,
        1,
        "es-empty",
        BigDecimal.ZERO,
        0L,
        0L,
        null,
        null);
    when(usage.findByRunIdOrderByCreatedAtAsc("es-empty")).thenReturn(List.of());

    BenchmarkTrackingService.UsageReconciliation r = service.reconcileUsage(run.getId());

    assertThat(r.updatedTasks()).isZero();
    assertThat(r.unlinkedAttempts()).isEqualTo(1);
    assertThat(r.attemptsWithNoLedgerRows()).isEqualTo(1);
  }
}
