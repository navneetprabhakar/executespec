package com.navneet.executespec.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * A4 (audit 2026-05-29) — pins the cooperative-interrupt check added to {@link WorkerAgent}'s tool
 * loop. Before A4 the worker never checked {@code Thread.isInterrupted()}, so a reviewer pass's
 * {@code future.cancel(true)} (or any orchestrator cancel) could not stop a runaway loop — it kept
 * generating to completion (still billed). These tests assert (1) an interrupt flag set before
 * {@code run()} terminates on iter 1 with NO LLM call, and (2) an interrupt raised mid-work (during
 * a tool call) stops the worker at the next iteration boundary — the same "stop on the next
 * boundary" contract the wall-clock cap uses.
 */
class WorkerAgentInterruptTest {

  /**
   * Tests set the current thread's interrupt flag; clear it so it can't leak into sibling tests.
   */
  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private static Tool tool(String name, boolean interruptOnExecute) {
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
        if (interruptOnExecute) {
          // Simulate an external cancel landing while the worker is mid-iteration.
          Thread.currentThread().interrupt();
        }
        return new ToolResult.Ok("ok");
      }
    };
  }

  private record Fixture(WorkerAgent worker, LlmRouter router) {}

  private Fixture newWorker(List<ChatResponse> seq, boolean toolsInterrupt) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null); // no budget guard — isolate the interrupt path
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));
    lenient()
        .when(costTracker.snapshot(anyString()))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    lenient()
        .when(registry.find(anyString()))
        .thenAnswer(inv -> tool(inv.getArgument(0), toolsInterrupt));
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());

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
            mock(com.navneet.executespec.sse.SseBroadcaster.class),
            null,
            null);
    return new Fixture(worker, router);
  }

  private static WorkerTaskRequest req(int maxIter) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .systemPrompt("system")
        .userPrompt("do thing")
        .context(new ExecutionContext("run-int", "T1", Path.of("/tmp/ws-interrupt")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(4096)
        .build();
  }

  @Test
  @DisplayName("interrupt flag set before run → terminates on iter 1 with NO LLM call")
  void interrupt_before_run_terminates_iter1_no_llm_call() {
    Fixture f = newWorker(List.of(), false); // no responses stubbed — the LLM must NOT be called
    Thread.currentThread().interrupt(); // simulate future.cancel(true) landing before the loop

    WorkerResult result = f.worker.run(req(40));

    assertEquals(
        WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE,
        result.outcome(),
        "A set interrupt flag must terminate the worker at the top-of-loop check.");
    assertEquals(1, result.iterations(), "Must bail at the iter-1 boundary, before any LLM call.");
    verify(f.router, never()).call(any());
  }

  @Test
  @DisplayName("interrupt raised during a tool call → stops at the next iteration boundary")
  void interrupt_mid_work_terminates_at_next_boundary() {
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq, true); // every tool call interrupts the worker thread

    WorkerResult result = f.worker.run(req(40));

    assertEquals(
        WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE,
        result.outcome(),
        "An interrupt raised mid-iteration must stop the worker, not run to completion.");
    assertEquals(
        2,
        result.iterations(),
        "Iter 1 runs the tool (which interrupts); iter 2's top-of-loop check stops it.");
  }
}
