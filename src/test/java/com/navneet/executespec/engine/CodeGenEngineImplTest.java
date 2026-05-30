package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.PatchAgent;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/**
 * N4 (audit 2026-05-29) — unit coverage for the Phase-2 wrap facade {@link CodeGenEngineImpl}: the
 * {@code WorkerResult}+cost → {@code EngineResult} mapping, the exhaustive outcome map, and both
 * fail-safe branches (workspace realpath failure, cost-snapshot exception). Pure mock test — no
 * LLM, no DB, no network.
 */
class CodeGenEngineImplTest {

  private final PatchAgent patchAgent = mock(PatchAgent.class);
  private final RunCostTracker costTracker = mock(RunCostTracker.class);
  private final CodeGenEngineImpl engine = new CodeGenEngineImpl(patchAgent, costTracker);

  private static EngineRequest req(Path workspace) {
    return EngineRequest.builder()
        .taskSpec("fix the login bug")
        .workspace(workspace)
        .provider("anthropic")
        .model("claude-sonnet-4-6")
        .runId("run-1")
        .taskId("task-1")
        .build();
  }

  private void stubWorker(WorkerResult wr) {
    when(patchAgent.run(anyString(), anyString(), anyString(), any(ExecutionContext.class)))
        .thenReturn(wr);
  }

  @Test
  void solve_mapsWorkerResultAndCostIntoEngineResult(@TempDir Path workspace) {
    stubWorker(
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(5)
            .toolCallsDispatched(10)
            .completionSummary("done")
            .toolCallLog(List.of("readFile ok", "writeFile ok"))
            .build());
    // Snapshot(inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, costUsd, calls)
    when(costTracker.snapshot("run-1"))
        .thenReturn(new RunCostTracker.Snapshot(100, 50, 0, 20, 1.25, 3));

    EngineResult r = engine.solve(req(workspace));

    assertThat(r.outcome()).isEqualTo(EngineOutcome.COMPLETED);
    assertThat(r.iterations()).isEqualTo(5);
    assertThat(r.toolCalls()).isEqualTo(10);
    assertThat(r.summary()).isEqualTo("done");
    assertThat(r.transcript()).containsExactly("readFile ok", "writeFile ok");
    // cacheReadTokens (4th arg) is what the facade lifts — not cacheWriteTokens.
    assertThat(r.cost().inputTokens()).isEqualTo(100L);
    assertThat(r.cost().outputTokens()).isEqualTo(50L);
    assertThat(r.cost().cacheReadTokens()).isEqualTo(20L);
    assertThat(r.cost().usd()).isEqualTo(1.25);
    // @TempDir is not a git repo → host-side `git diff` exits non-zero → empty diff, no throw.
    assertThat(r.diff()).isEmpty();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "COMPLETED,COMPLETED",
    "COMPLETED_BY_STAGNATION,STAGNATED",
    "ITERATION_LIMIT_EXCEEDED,ITER_LIMIT",
    "BUDGET_EXCEEDED,BUDGET",
    "WALL_CLOCK_EXCEEDED,WALL_CLOCK",
    "STOPPED_WITHOUT_COMPLETE,STOPPED",
    "ERROR,ERROR"
  })
  void solve_mapsEveryWorkerOutcome(
      String workerOutcome, String engineOutcome, @TempDir Path workspace) {
    stubWorker(
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.valueOf(workerOutcome))
            .iterations(1)
            .build());
    when(costTracker.snapshot(anyString()))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));

    EngineResult r = engine.solve(req(workspace));

    assertThat(r.outcome()).isEqualTo(EngineOutcome.valueOf(engineOutcome));
  }

  @Test
  void solve_returnsErrorWhenWorkspaceRealpathFails() {
    // A non-existent path → Path.toRealPath() throws IOException → ERROR result, worker untouched.
    EngineResult r = engine.solve(req(Path.of("/definitely-not-a-real-path-9f3a2", "ws")));

    assertThat(r.outcome()).isEqualTo(EngineOutcome.ERROR);
    assertThat(r.summary()).contains("workspace realpath failed");
    assertThat(r.cost()).isEqualTo(EngineCost.zero());
    assertThat(r.iterations()).isZero();
    assertThat(r.diff()).isEmpty();
    verifyNoInteractions(patchAgent);
  }

  @Test
  void solve_costSnapshotFailureFallsBackToZeroWithoutFailingTheSolve(@TempDir Path workspace) {
    stubWorker(
        WorkerResult.builder().outcome(WorkerResult.Outcome.COMPLETED).iterations(1).build());
    when(costTracker.snapshot(anyString())).thenThrow(new RuntimeException("redis down"));

    EngineResult r = engine.solve(req(workspace));

    assertThat(r.cost()).isEqualTo(EngineCost.zero());
    assertThat(r.outcome())
        .isEqualTo(EngineOutcome.COMPLETED); // cost failure must not fail the run
  }

  @Test
  void solve_nullTaskIdDefaultsToRoot(@TempDir Path workspace) {
    stubWorker(WorkerResult.builder().outcome(WorkerResult.Outcome.COMPLETED).build());
    when(costTracker.snapshot(anyString()))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    EngineRequest request =
        EngineRequest.builder()
            .taskSpec("x")
            .workspace(workspace)
            .provider("anthropic")
            .model("m")
            .runId("run-1")
            .taskId(null)
            .build();

    engine.solve(request);

    ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
    verify(patchAgent).run(anyString(), anyString(), anyString(), ctx.capture());
    assertThat(ctx.getValue().taskId()).isEqualTo("__root__");
  }
}
