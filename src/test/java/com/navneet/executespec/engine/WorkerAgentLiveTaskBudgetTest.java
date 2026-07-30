package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class WorkerAgentLiveTaskBudgetTest {

  @Test
  void disabledTokenAndCostCeilingsDoNotBlockAnotherTurn() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(900_000, 200_000, 0, 0, 9.0, 10));

    assertThat(WorkerLiveBudgetGuard.taskViolation(request(null, null), tracker, 0L)).isNull();
  }

  @Test
  void configuredTokenCeilingUsesTaskScopedInputPlusOutput() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(700, 301, 0, 0, 0.1, 2));

    assertThat(WorkerLiveBudgetGuard.taskViolation(request(1_000L, null), tracker, 0L))
        .contains("task token ceiling 1000 reached")
        .contains("input+output=1001");
  }

  @Test
  void perSolveDollarCapUsesTaskScopedSpend() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(10, 10, 0, 0, 2.01, 2));

    assertThat(WorkerLiveBudgetGuard.taskViolation(request(null, 2.0), tracker, 0L))
        .contains("task cost ceiling $2.00 reached");
  }

  @Test
  void wallClockCeilingIsCheckedBetweenTurns() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    WorkerTaskRequest req = request(null, null, 1);

    assertThat(
            WorkerLiveBudgetGuard.taskViolation(req, tracker, System.currentTimeMillis() - 2_000L))
        .isEqualTo("task wall-clock ceiling reached");
  }

  @Test
  void terminalFinalizerIgnoresWallClockButStillHonorsCostCap() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    long expiredStart = System.currentTimeMillis() - 2_000L;

    assertThat(
            WorkerLiveBudgetGuard.finalizerViolation(request(null, null, 1), tracker, expiredStart))
        .isNull();

    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 2.01, 1));
    assertThat(
            WorkerLiveBudgetGuard.finalizerViolation(request(null, 2.0, 1), tracker, expiredStart))
        .contains("task cost ceiling $2.00 reached");
  }

  @Test
  void crossedTaskCapRefusesToolBeforeExecution() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(900, 101, 0, 0, 0.1, 1));
    ToolRegistry registry = mock(ToolRegistry.class);
    Tool tool = mock(Tool.class);
    when(registry.find("readFile")).thenReturn(tool);
    WorkerAgent agent = worker(tracker, mock(LlmRouter.class), registry);
    WorkerTaskRequest req = request(1_000L, null);
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c1", "function", "readFile", "{\"path\":\"a\"}");

    String envelope = agent.dispatchOne(call, req, WorkerDispatchMode.NORMAL);

    assertThat(envelope).contains("BUDGET_EXCEEDED").contains("task token ceiling");
    verify(tool, never())
        .execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void wallExpiredFinalizerDispatchExecutesWriteAndShell() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    ToolRegistry registry = mock(ToolRegistry.class);
    Tool tool = mock(Tool.class);
    when(registry.find(org.mockito.ArgumentMatchers.anyString())).thenReturn(tool);
    when(tool.inputSchema()).thenReturn("{\"type\":\"object\"}");
    when(tool.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new com.navneet.executespec.tool.ToolResult.Ok("executed"));
    WorkerAgent agent = worker(tracker, mock(LlmRouter.class), registry);
    WorkerTaskRequest expired =
        request(null, null, 1).toBuilder()
            .workerStartedAtEpochMs(System.currentTimeMillis() - 2_000L)
            .build();

    String write =
        agent.dispatchOne(
            new AssistantMessage.ToolCall(
                "w1", "function", "writeFile", "{\"path\":\"a\",\"content\":\"x\"}"),
            expired,
            WorkerDispatchMode.FINALIZER);
    String shell =
        agent.dispatchOne(
            new AssistantMessage.ToolCall("s1", "function", "shell", "{\"command\":\"test -f a\"}"),
            expired,
            WorkerDispatchMode.FINALIZER);

    assertThat(write).contains("\"ok\":true");
    assertThat(shell).contains("\"ok\":true");
    verify(tool, org.mockito.Mockito.times(2))
        .execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void finalizerDispatchStillRefusesCostAndTokenCeilings() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    Tool tool = mock(Tool.class);
    when(registry.find("writeFile")).thenReturn(tool);
    WorkerAgent agent = worker(tracker, mock(LlmRouter.class), registry);
    AssistantMessage.ToolCall write =
        new AssistantMessage.ToolCall(
            "w1", "function", "writeFile", "{\"path\":\"a\",\"content\":\"x\"}");

    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 2.01, 1));
    String costRefusal = agent.dispatchOne(write, request(null, 2.0), WorkerDispatchMode.FINALIZER);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(900, 101, 0, 0, 0.1, 1));
    String tokenRefusal =
        agent.dispatchOne(write, request(1_000L, null), WorkerDispatchMode.FINALIZER);

    assertThat(costRefusal).contains("BUDGET_EXCEEDED").contains("task cost ceiling");
    assertThat(tokenRefusal).contains("BUDGET_EXCEEDED").contains("task token ceiling");
    verify(tool, never())
        .execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void crossedTaskCapStopsBeforeProviderCall() {
    RunCostTracker tracker = mock(RunCostTracker.class);
    when(tracker.snapshotTask("run-budget", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(900, 101, 0, 0, 0.1, 1));
    LlmRouter router = mock(LlmRouter.class);
    WorkerAgent agent = worker(tracker, router, mock(ToolRegistry.class));

    WorkerResult result = agent.run(request(1_000L, null));

    assertThat(result.outcome()).isEqualTo(WorkerResult.Outcome.BUDGET_EXCEEDED);
    verify(router, never()).call(org.mockito.ArgumentMatchers.any());
  }

  private static WorkerTaskRequest request(Long tokenCap, Double dollarCap) {
    return request(tokenCap, dollarCap, null);
  }

  private static WorkerTaskRequest request(Long tokenCap, Double dollarCap, Integer wallSeconds) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("test-model")
        .userPrompt("fix")
        .context(new ExecutionContext("run-budget", "T1", Path.of("/tmp/run-budget")))
        .taskTotalTokenCap(tokenCap)
        .dollarCap(dollarCap)
        .wallClockSeconds(wallSeconds)
        .workerStartedAtEpochMs(System.currentTimeMillis())
        .build();
  }

  private static WorkerAgent worker(RunCostTracker tracker) {
    return worker(tracker, mock(LlmRouter.class), mock(ToolRegistry.class));
  }

  private static WorkerAgent worker(
      RunCostTracker tracker, LlmRouter router, ToolRegistry registry) {
    return new WorkerAgent(
        router,
        registry,
        new ObjectMapper(),
        mock(ContextStore.class),
        mock(Metrics.class),
        tracker,
        mock(PipelineRunRepository.class),
        mock(SseBroadcaster.class),
        null,
        null,
        null);
  }
}
