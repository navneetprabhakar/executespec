package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class WorkerAutoVerifyProgressTest {

  @Test
  void firstPostEditObservationComparesAgainstTheServerObservedRedState() {
    WorkspaceTestRunner runner = mock(WorkspaceTestRunner.class);
    when(runner.runTestsDetailed(anyString(), any()))
        .thenReturn(
            result(4, 2, 2, 0, "A failed in 9.8s\nB failed"),
            result(4, 3, 1, 0, "C failed"),
            result(4, 1, 3, 0, "A failed\nC failed\nD failed"),
            result(4, 4, 0, 0, "4 passed"));
    WorkerAutoVerifyProgress progress =
        new WorkerAutoVerifyProgress(
            DeterministicFailureDelta.snapshot(2, 2, 0, 4, "A failed in 0.12s\nB failed"));
    ExecutionContext context = new ExecutionContext("run-1", "T1", Path.of("/tmp/workspace"));

    WorkerAutoVerifyProgress.Result unchanged = progress.observe(runner, context, 1);
    WorkerAutoVerifyProgress.Result improved = progress.observe(runner, context, 1);
    WorkerAutoVerifyProgress.Result regressed = progress.observe(runner, context, 1);
    WorkerAutoVerifyProgress.Result passed = progress.observe(runner, context, 1);

    assertThat(unchanged.delta()).isEqualTo(DeterministicFailureDelta.Kind.UNCHANGED);
    assertThat(unchanged.commandClass()).isEqualTo("NARROWEST_AVAILABLE");
    assertThat(unchanged.elapsed()).isGreaterThanOrEqualTo(java.time.Duration.ZERO);
    assertThat(unchanged.message()).contains("AUTO-VERIFY [NARROWEST_AVAILABLE]");
    assertThat(unchanged.resetsProgress()).isFalse();
    assertThat(improved.delta()).isEqualTo(DeterministicFailureDelta.Kind.IMPROVED);
    assertThat(improved.resetsProgress()).isTrue();
    assertThat(regressed.delta()).isEqualTo(DeterministicFailureDelta.Kind.REGRESSED);
    assertThat(regressed.resetsProgress()).isFalse();
    assertThat(passed.delta()).isEqualTo(DeterministicFailureDelta.Kind.PASSED);
    assertThat(passed.resetsProgress()).isTrue();
  }

  @Test
  void absentOrEmptyDeterministicEvidenceDoesNotInventAComparison() {
    WorkspaceTestRunner runner = mock(WorkspaceTestRunner.class);
    when(runner.runTestsDetailed(anyString(), any()))
        .thenReturn(Optional.empty(), result(0, 0, 0, 0, "no tests"));
    WorkerAutoVerifyProgress progress = new WorkerAutoVerifyProgress();
    ExecutionContext context = new ExecutionContext("run-1", "T1", Path.of("/tmp/workspace"));

    assertThat(progress.observe(runner, context, 1)).isNull();
    assertThat(progress.observe(runner, context, 1)).isNull();
  }

  private static Optional<WorkspaceTestRunner.DetailedResult> result(
      int total, int passed, int failed, int errors, String output) {
    TestExecutionSummary summary =
        TestExecutionSummary.builder()
            .totalTests(total)
            .passed(passed)
            .failed(failed)
            .errors(errors)
            .build();
    return Optional.of(
        new WorkspaceTestRunner.DetailedResult(
            summary, output, "python3 -m pytest -q", failed + errors == 0 ? 0 : 1));
  }
}
