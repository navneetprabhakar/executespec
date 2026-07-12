package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;

import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;

import org.junit.jupiter.api.Test;

class LowCodingFixtureHarnessTest {

  @Test
  void fixtureIsDeterministicAndRequiresReservation() throws Exception {
    var root = Files.createTempDirectory("es-low-fixture-");
    try {
      WorkerAgent worker = mock(WorkerAgent.class);
      when(worker.run(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                Files.writeString(
                    root.resolve("calculator.py"), "def add(a, b):\n    return a + b\n");
                return WorkerResult.builder()
                    .outcome(WorkerResult.Outcome.COMPLETED)
                    .iterations(2)
                    .toolCallsDispatched(3)
                    .completionSummary("done")
                    .finalAssistantText("")
                    .repeatedAssistantTextCount(0)
                    .toolCallLog(java.util.List.of())
                    .totalLatencyMs(1)
                    .build();
              });

      var evidence =
          LowCodingFixtureHarness.run(
              worker, "deepseek", "deepseek-v4-flash-native", "run-low-1", "reservation-1", root);

      org.assertj.core.api.Assertions.assertThat(evidence.tier()).isEqualTo("LOW");
      org.assertj.core.api.Assertions.assertThat(evidence.evaluatorPassed()).isTrue();
      org.assertj.core.api.Assertions.assertThat(evidence.promptFingerprint()).hasSize(64);
      org.assertj.core.api.Assertions.assertThat(evidence.toolSchemaFingerprint()).hasSize(64);
      org.assertj.core.api.Assertions.assertThat(evidence.harnessFingerprint()).hasSize(64);
    } finally {
      try (var walk = Files.walk(root)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @Test
  void blankReservationFailsBeforeWorkerDispatch() throws Exception {
    WorkerAgent worker = mock(WorkerAgent.class);
    var root = Files.createTempDirectory("es-low-fixture-");
    try {
      assertThatThrownBy(
              () ->
                  LowCodingFixtureHarness.run(
                      worker, "deepseek", "deepseek-v4-flash-native", "run-low-2", "", root))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reservationId");
      org.mockito.Mockito.verifyNoInteractions(worker);
    } finally {
      try (var walk = Files.walk(root)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }
}
