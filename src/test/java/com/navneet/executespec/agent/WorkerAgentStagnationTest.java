package com.navneet.executespec.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Pins the stagnation guard introduced for the run {@code a076c876} task T2 runaway (2026-05-03).
 * The worker burned 22 iterations re-reading + verifying its own writes after the change set was
 * already applied; the new guard injects a one-shot PROGRESS CHECK at {@link
 * WorkerAgent#STAGNATION_WARN_AFTER_N} consecutive non-write iterations and force-terminates with
 * {@link WorkerResult.Outcome#COMPLETED_BY_STAGNATION} at {@link
 * WorkerAgent#STAGNATION_FORCE_AFTER_N}.
 *
 * <p>Tests stub the LLM router so each iteration returns a deterministic tool call. {@code
 * readFile} counts as a non-write iteration; {@code writeFile} resets the stagnation counter.
 *
 * <p>Tool dispatch is intercepted through a fake {@link ToolRegistry} so we don't need a real
 * filesystem.
 */
class WorkerAgentStagnationTest {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  /** Fake tool that returns Ok for any input. Used as the registry's stand-in for read/write. */
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

  private record Fixture(
      WorkerAgent worker,
      LlmRouter router,
      ToolRegistry registry,
      List<ChatResponse> responsesIssued) {}

  /** Build a worker pre-stubbed to issue the supplied response sequence on consecutive calls. */
  private Fixture newWorker(List<ChatResponse> responseSequence) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null); // disable budget guard for these tests
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));

    // Tool look-ups always return a stub that succeeds.
    lenient().when(registry.find(anyString())).thenAnswer(inv -> fakeTool(inv.getArgument(0)));
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());

    // Stub the router to issue the prepared response sequence in order.
    if (!responseSequence.isEmpty()) {
      ChatResponse first = responseSequence.get(0);
      ChatResponse[] rest =
          responseSequence.subList(1, responseSequence.size()).toArray(new ChatResponse[0]);
      lenient().when(router.call(any())).thenReturn(first, rest);
    }

    // 2026-05-04 — WorkerAgent gained an SseBroadcaster dep on PR #71 (quirky LiveLog
    // emitters). Tests use a Mockito mock so the SSE emit path is a no-op; the agent's logic
    // (stagnation guard + iteration loop) is what's under test, not the live-log surface.
    WorkerAgent worker =
        new WorkerAgent(
            router,
            registry,
            new ObjectMapper(),
            contextStore,
            metrics,
            costTracker,
            runRepo,
            org.mockito.Mockito.mock(com.navneet.executespec.sse.SseBroadcaster.class));
    return new Fixture(worker, router, registry, responseSequence);
  }

  private static WorkerTaskRequest req(int maxIter) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .systemPrompt("system")
        .userPrompt("do thing")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-stagnation")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(4096)
        .build();
  }

  @Test
  void stagnation_force_terminates_after_FORCE_threshold_iterations_without_writes() {
    // 20 consecutive readFile calls — exceeds STAGNATION_FORCE_AFTER_N (12).
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(40));

    assertEquals(
        WorkerResult.Outcome.COMPLETED_BY_STAGNATION,
        result.outcome(),
        "Worker should force-terminate with COMPLETED_BY_STAGNATION after "
            + WorkerAgent.STAGNATION_FORCE_AFTER_N
            + " stagnant iters. Saw: "
            + result.outcome());
    assertEquals(
        WorkerAgent.STAGNATION_FORCE_AFTER_N,
        result.iterations(),
        "Should terminate exactly at the force threshold, not later.");
  }

  @Test
  void writeFile_resets_stagnation_counter() {
    // 7 reads (one shy of the warn threshold) + 1 writeFile + 7 more reads + completeTask.
    // Without the reset this would warn-then-force at iter 13. With the reset, the post-write
    // 7 reads only reach stagnant=7 — below the warn threshold — and the worker completes
    // cleanly via completeTask.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("writeFile", "{\"path\":\"y\",\"content\":\"z\"}")));
    for (int i = 0; i < 7; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(40));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(16, result.iterations(), "Should complete on the completeTask call (iter 16).");
  }

  @Test
  void editFile_also_counts_as_progress_and_resets_counter() {
    // Same shape as the writeFile test but with editFile (the other write-class tool).
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("editFile", "{\"path\":\"y\",\"edits\":[]}")));
    for (int i = 0; i < 7; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(40));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
  }

  @Test
  void warning_threshold_is_strictly_below_force_threshold() {
    // Sanity assertion to keep the two thresholds in the right relative order even if a
    // future tuning change moves both. STAGNATION_FORCE_AFTER_N must give the worker at least
    // one post-warning iter to self-correct.
    assertTrue(
        WorkerAgent.STAGNATION_WARN_AFTER_N < WorkerAgent.STAGNATION_FORCE_AFTER_N,
        "WARN threshold must be strictly less than FORCE threshold so the worker has a"
            + " chance to call completeTask after the warning fires.");
    assertTrue(
        WorkerAgent.STAGNATION_FORCE_AFTER_N - WorkerAgent.STAGNATION_WARN_AFTER_N >= 2,
        "There should be at least 2 post-warning iterations of grace so a slow model still"
            + " has a chance to react.");
  }

  @Test
  void empty_tool_calls_does_not_blow_up_stagnation_counter() {
    // If the model returns no tool calls at all, the worker should exit immediately with
    // STOPPED_WITHOUT_COMPLETE — the stagnation guard must not preempt that path.
    AssistantMessage emptyMsg = AssistantMessage.builder().content("done?").build();
    ChatResponse emptyResp = new ChatResponse(List.of(new Generation(emptyMsg)));
    Fixture f = newWorker(List.of(emptyResp));

    WorkerResult result = f.worker.run(req(40));

    assertEquals(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, result.outcome());
    assertEquals(1, result.iterations());
    assertFalse(
        result.outcome() == WorkerResult.Outcome.COMPLETED_BY_STAGNATION,
        "An empty-toolcalls response is not stagnation — exit should be STOPPED_WITHOUT_COMPLETE.");
  }
}
