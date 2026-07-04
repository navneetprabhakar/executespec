package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BenchmarkTrackingServiceTest {

  private final BenchmarkRunRepository runs = mock(BenchmarkRunRepository.class);
  private final BenchmarkTaskResultRepository taskResults =
      mock(BenchmarkTaskResultRepository.class);
  private final BenchmarkEventRepository events = mock(BenchmarkEventRepository.class);
  private final SseBroadcaster sse = mock(SseBroadcaster.class);
  private final Map<String, BenchmarkRun> runStore = new HashMap<>();
  private final List<BenchmarkTaskResult> taskStore = new ArrayList<>();
  private final List<BenchmarkEvent> eventStore = new ArrayList<>();

  private BenchmarkTrackingService service;

  @BeforeEach
  void setUp() {
    service = new BenchmarkTrackingService(runs, taskResults, events, sse, new ObjectMapper());

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

    doAnswer(
            inv -> {
              BenchmarkTaskResult result = inv.getArgument(0);
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
}
