package com.navneet.executespec.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * VBC verify-before-complete (RL-2026-06-…, {@code WorkerTaskRequest#verifyBeforeComplete}, flag
 * {@code worker.verify-before-complete.enabled}, V235) — pins the server-RUN test gate at {@code
 * completeTask}. Unlike the model-dependent {@code requireTestVerification}/{@code
 * requireBuildVerification} soft gates, this RUNS the project's tests itself via {@link
 * WorkspaceTestRunner} (called DIRECTLY, decoupled from V217) and refuses completion when they are
 * RED — bounded to {@link WorkerAgent#VBC_GATE_MAX_REFUSALS}, then YIELDS (fail-open). It is inert
 * (fail-open) when the flag is off, the runner is unwired, no runner resolves, zero tests ran, or
 * the run is remote.
 *
 * <p>The worker emits {@code completeTask} directly (no writes needed — VBC is independent of
 * writes); every other gate is left off so VBC is isolated.
 */
class WorkerAgentVerifyBeforeCompleteTest {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private static TestExecutionSummary summary(int total, int passed, int failed, int errors) {
    return TestExecutionSummary.builder()
        .totalTests(total)
        .passed(passed)
        .failed(failed)
        .errors(errors)
        .build();
  }

  private record Fixture(WorkerAgent worker) {}

  /**
   * @param remote whether isRemoteRun is true (VBC fail-opens on remote — host runner can't reach a
   *     container)
   * @param runnerResult what {@link WorkspaceTestRunner#runTests} returns (null ⇒ inject a null
   *     runner, i.e. the gate is unwired)
   */
  private Fixture newWorker(
      List<ChatResponse> seq, boolean remote, Optional<TestExecutionSummary> runnerResult) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));
    lenient().when(registry.isRemoteRun(any())).thenReturn(remote);

    WorkspaceTestRunner runner = null;
    if (runnerResult != null) {
      runner = mock(WorkspaceTestRunner.class);
      lenient().when(runner.runTests(anyString(), any())).thenReturn(runnerResult);
      // Task 8: the VBC gate now uses the detailed variant (failure tail for the repair message).
      lenient()
          .when(runner.runTestsDetailed(anyString(), any()))
          .thenReturn(
              runnerResult.map(
                  s ->
                      new WorkspaceTestRunner.DetailedResult(
                          s,
                          "FAILED test_x — AssertionError",
                          "python3 -m pytest -q",
                          s.getFailed() > 0 || s.getErrors() > 0 ? 1 : 0)));
    }

    if (!seq.isEmpty()) {
      ChatResponse first = seq.get(0);
      ChatResponse[] rest = seq.subList(1, seq.size()).toArray(new ChatResponse[0]);
      lenient().when(router.call(any())).thenReturn(first, rest);
    }

    WorkerAgent worker =
        new WorkerAgent(
            router,
            registry,
            new ObjectMapper(),
            contextStore,
            metrics,
            costTracker,
            runRepo,
            org.mockito.Mockito.mock(com.navneet.executespec.sse.SseBroadcaster.class),
            null,
            null,
            runner);
    return new Fixture(worker);
  }

  private static WorkerTaskRequest req(boolean verifyBeforeComplete) {
    int maxIter = 20;
    return WorkerTaskRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4-flash")
        .systemPrompt("system")
        .userPrompt("fix the bug")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-vbc")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(8192)
        .stagnationWarnAfter(maxIter)
        .stagnationForceAfter(maxIter)
        .verifyBeforeComplete(verifyBeforeComplete)
        .build();
  }

  private static List<ChatResponse> completeTaskTimes(int n) {
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    }
    return seq;
  }

  @Test
  void refuses_red_tests_then_yields_after_bounded_refusals() {
    // Tests RED every attempt → refuse on iters 1,2,3 (3 refusals == MAX), then YIELD on iter 4.
    Fixture f = newWorker(completeTaskTimes(6), false, Optional.of(summary(5, 3, 2, 0)));

    WorkerResult result = f.worker.run(req(true));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "VBC must refuse a red suite but eventually YIELD (fail-open). Saw: " + result.outcome());
    assertEquals(
        4,
        result.iterations(),
        "refused on iters 1,2,3 (== VBC_GATE_MAX_REFUSALS), then yields + completes on iter 4.");
  }

  @Test
  void refuses_on_errors_too() {
    // errors>0 (failed==0) is also RED.
    Fixture f = newWorker(completeTaskTimes(6), false, Optional.of(summary(5, 4, 0, 1)));

    WorkerResult result = f.worker.run(req(true));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(4, result.iterations(), "errored tests are red → 3 refusals then yield.");
  }

  @Test
  void permits_immediately_when_tests_green() {
    // failed==0 && errors==0 && total>0 → green → completeTask permitted on the first try.
    Fixture f = newWorker(completeTaskTimes(2), false, Optional.of(summary(5, 5, 0, 0)));

    WorkerResult result = f.worker.run(req(true));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations(), "green suite → no refusal, completes on iter 1.");
  }

  @Test
  void fail_open_when_no_runner_resolves() {
    // Optional.empty() = no recognized runner / unparseable → fail-open (permit).
    Fixture f = newWorker(completeTaskTimes(2), false, Optional.empty());

    WorkerResult result = f.worker.run(req(true));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations(), "no runner → fail-open, completes on iter 1.");
  }

  @Test
  void fail_open_when_zero_tests() {
    // total==0 → nothing ran → fail-open (don't block on a suite that didn't execute).
    Fixture f = newWorker(completeTaskTimes(2), false, Optional.of(summary(0, 0, 0, 0)));

    WorkerResult result = f.worker.run(req(true));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations(), "zero tests → fail-open, completes on iter 1.");
  }

  @Test
  void inert_when_remote() {
    // Remote run → host-side runner can't reach the container → fail-open even with a red result.
    Fixture f = newWorker(completeTaskTimes(2), true, Optional.of(summary(5, 0, 5, 0)));

    WorkerResult result = f.worker.run(req(true));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations(), "remote → VBC inert, completes on iter 1.");
  }

  @Test
  void inert_when_runner_unwired() {
    // workspaceTestRunner == null (legacy call sites) → fail-open.
    Fixture f = newWorker(completeTaskTimes(2), false, null);

    WorkerResult result = f.worker.run(req(true));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations(), "unwired runner → fail-open, completes on iter 1.");
  }

  @Test
  void inert_when_flag_off() {
    // verifyBeforeComplete=false → gate never runs, even with a red suite wired.
    Fixture f = newWorker(completeTaskTimes(2), false, Optional.of(summary(5, 0, 5, 0)));

    WorkerResult result = f.worker.run(req(false));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations(), "flag off → VBC inert, completes on iter 1.");
  }
}
