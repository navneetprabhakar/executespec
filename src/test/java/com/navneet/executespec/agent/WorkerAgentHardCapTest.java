package com.navneet.executespec.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * P3.1 (GA-readiness, 2026-05-06) — pins the in-loop budget guard on {@link WorkerAgent}.
 *
 * <p>The cap mechanism was already shipped on this branch (lines 211-238 of WorkerAgent — every
 * {@link WorkerAgent#BUDGET_CHECK_EVERY_N_ITERATIONS} iterations, the worker reads {@link
 * RunCostTracker#snapshot} and force-terminates with {@link WorkerResult.Outcome#BUDGET_EXCEEDED}
 * when {@code spent &gt;= hardCapUsd}). The audit identified that the absence of a regression test
 * left the runaway-cost class untested. This test class pins the contract.
 *
 * <p>Test pattern: stub the LLM router with an infinite tool-call sequence + an incrementing
 * cost-snapshot, set a small {@code hardCapUsd}, and assert the worker terminates with
 * BUDGET_EXCEEDED at the right iteration boundary.
 */
class WorkerAgentHardCapTest {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private static Tool fakeTool(String name) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "stub";
      }

      @Override
      public String inputSchema() {
        return "{\"type\":\"object\",\"additionalProperties\":false}";
      }

      @Override
      public ToolResult execute(JsonNode args, ExecutionContext ctx) {
        return new ToolResult.Ok("ok");
      }
    };
  }

  private record Fixture(WorkerAgent worker) {}

  private Fixture newWorker(Double hardCap, double costPerIter, List<ChatResponse> seq) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(hardCap);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));

    AtomicInteger callCount = new AtomicInteger(0);
    lenient()
        .when(costTracker.snapshot(anyString()))
        .thenAnswer(
            inv ->
                new RunCostTracker.Snapshot(
                    0, 0, 0, 0, costPerIter * callCount.incrementAndGet(), 0));

    lenient().when(registry.find(anyString())).thenAnswer(inv -> fakeTool(inv.getArgument(0)));
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());

    if (!seq.isEmpty()) {
      ChatResponse first = seq.get(0);
      ChatResponse[] rest = seq.subList(1, seq.size()).toArray(new ChatResponse[0]);
      when(router.call(any())).thenReturn(first, rest);
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
            mock(com.navneet.executespec.sse.SseBroadcaster.class),
            // RL-2026-05-052 Phase 5+6 — ToolOutputCompactor; null = compactor disabled.
            null);
    return new Fixture(worker);
  }

  private static WorkerTaskRequest req(int maxIter) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .systemPrompt("system")
        .userPrompt("do thing")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-budget")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(4096)
        .build();
  }

  @Test
  void budget_exceeded_force_terminates_at_cap() {
    // hardCap = $0.50, costPerIter = $0.20 (so iter 1 = $0.20, iter 2 = $0.40, iter 3 = $0.60).
    // Budget check fires on iter 1, then iter 3 (BUDGET_CHECK_EVERY_N_ITERATIONS = 3).
    // At iter 3, snapshot returns $0.60 (above $0.50 cap) → BUDGET_EXCEEDED.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(0.50, 0.20, seq);

    WorkerResult result = f.worker.run(req(40));

    assertEquals(
        WorkerResult.Outcome.BUDGET_EXCEEDED,
        result.outcome(),
        "Worker must force-terminate with BUDGET_EXCEEDED when costTracker reports spend ≥"
            + " hardCapUsd at a budget-check iteration. Got: "
            + result.outcome());
  }

  @Test
  void budget_within_cap_does_not_terminate() {
    // hardCap = $5.00, costPerIter = $0.05 — across 30 iters total spend reaches $1.50, well
    // under the cap. The worker proceeds normally; without a completeTask call it ends in
    // ITERATION_LIMIT_EXCEEDED. The point of this test is asserting it does NOT trigger
    // BUDGET_EXCEEDED prematurely.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 35; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(5.00, 0.05, seq);

    WorkerResult result = f.worker.run(req(30));

    assertTrue(
        result.outcome() != WorkerResult.Outcome.BUDGET_EXCEEDED,
        "Spend was under cap; worker must not have terminated with BUDGET_EXCEEDED. Got: "
            + result.outcome());
  }

  @Test
  void null_cap_disables_guard() {
    // Some legacy runs predate the hardCapUsd column; pipeline_run.hard_cap_usd is null. The
    // worker must not crash + must not force-terminate when the cap is absent.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(null, 100.0 /* huge cost — irrelevant w/o cap */, seq);

    WorkerResult result = f.worker.run(req(30));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "Null cap means no enforcement; worker should reach completeTask normally.");
  }

  @Test
  void zero_cap_disables_guard() {
    // hardCapUsd = 0.0 (sentinel for "disabled" in some legacy persistence paths). The guard
    // condition `hardCapUsd > 0` skips the check.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(0.0, 100.0, seq);

    WorkerResult result = f.worker.run(req(30));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
  }

  @Test
  void check_fires_on_iter_one_when_already_over_cap() {
    // Iter 1 is in the budget-check set. If the run was already over cap before the worker
    // started (e.g., previous tasks burned the budget), the worker must terminate immediately.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(0.10, 5.00, seq); // iter 1 snapshot = $5 ≫ $0.10

    WorkerResult result = f.worker.run(req(30));

    assertEquals(WorkerResult.Outcome.BUDGET_EXCEEDED, result.outcome());
    assertEquals(1, result.iterations(), "Should terminate on iter 1 — already over cap.");
  }
}
