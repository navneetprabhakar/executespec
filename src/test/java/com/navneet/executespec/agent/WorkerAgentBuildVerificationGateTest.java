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
 * 2026-05-08 — pins the build-verification gate added for the FINAL_BUILD_FAILED reviewer
 * regression. When {@code requireBuildVerification=true} on a {@link WorkerTaskRequest}, the
 * worker's {@code completeTask} dispatch is refused unless a successful {@code buildProject} call
 * (exit=0) has landed within {@link WorkerAgent#BUILD_VERIFICATION_RECENCY_ITERS} iterations.
 *
 * <p>The gate exists for {@link com.navneet.executespec.reviewer.ReviewerAgent}: when a review pass
 * starts on a workspace whose build was failing pre-review, the reviewer must produce edits that
 * re-pass the build before declaring done. Without the gate, the reviewer often calls completeTask
 * after a few exploratory edits without re-running the build — leaving the run in
 * PARTIAL/FINAL_BUILD_FAILED. Mirrors V182 layer-3's runTests gate (see {@link
 * WorkerAgent#TEST_VERIFICATION_RECENCY_ITERS}).
 */
class WorkerAgentBuildVerificationGateTest {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  /** Fake tool — returns the supplied envelope when invoked. Used to control buildProject exit. */
  private static Tool fakeBuildTool(int exitCode) {
    return new Tool() {
      @Override
      public String name() {
        return "buildProject";
      }

      @Override
      public String description() {
        return "stub build";
      }

      @Override
      public String inputSchema() {
        return "{\"type\":\"object\",\"additionalProperties\":false}";
      }

      @Override
      public ToolResult execute(JsonNode args, ExecutionContext ctx) {
        // Match BuildProjectTool's content shape: command=...\nsource=...\nexit=N\noutput:...
        // The gate looks for the literal substring `\nexit=0\n` after JSON-encoding.
        return new ToolResult.Ok(
            "command=mvn compile\nsource=stub\nexit=" + exitCode + "\noutput:\nstubbed");
      }
    };
  }

  /** Fake non-build tool that always returns Ok. Stand-in for readFile/editFile/etc. */
  private static Tool fakeOtherTool(String name) {
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

  private record Fixture(WorkerAgent worker, LlmRouter router, ToolRegistry registry) {}

  /** Build a worker stubbed to issue the supplied response sequence on consecutive calls. */
  private Fixture newWorker(List<ChatResponse> responseSequence, int buildExit) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));

    // Tool look-up: buildProject returns the configured-exit fake; everything else returns ok.
    lenient()
        .when(registry.find(anyString()))
        .thenAnswer(
            inv -> {
              String n = inv.getArgument(0);
              return "buildProject".equals(n) ? fakeBuildTool(buildExit) : fakeOtherTool(n);
            });
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());

    if (!responseSequence.isEmpty()) {
      ChatResponse first = responseSequence.get(0);
      ChatResponse[] rest =
          responseSequence.subList(1, responseSequence.size()).toArray(new ChatResponse[0]);
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
            // RL-2026-05-052 Phase 5+6 — ToolOutputCompactor; null = compactor disabled.
            null,
            // RL-2026-05-052 follow-up — WorkerHistoryCompactor; null = history compaction
            // disabled.
            null);
    return new Fixture(worker, router, registry);
  }

  /** Build a request with the build-verification gate enabled. */
  private static WorkerTaskRequest gatedReq(int maxIter) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-sonnet-4-6")
        .systemPrompt("system")
        .userPrompt("fix the build")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-build-gate")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(8192)
        .stagnationWarnAfter(maxIter) // disable stagnation guard
        .stagnationForceAfter(maxIter)
        .requireBuildVerification(true)
        .build();
  }

  /** Same shape as gatedReq but gate disabled — for regression coverage of the default path. */
  private static WorkerTaskRequest ungatedReq(int maxIter) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-sonnet-4-6")
        .systemPrompt("system")
        .userPrompt("fix the build")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-build-gate")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(8192)
        .stagnationWarnAfter(maxIter)
        .stagnationForceAfter(maxIter)
        // requireBuildVerification not set → defaults to null/false
        .build();
  }

  @Test
  void completeTask_refused_when_no_buildProject_called_and_gate_on() {
    // Agent immediately calls completeTask without ever running buildProject.
    // Gate must refuse, then the agent (still calling completeTask each iter) keeps being
    // refused until it hits the iteration cap.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    }
    Fixture f = newWorker(seq, 0);

    WorkerResult result = f.worker.run(gatedReq(5));

    assertEquals(
        WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED,
        result.outcome(),
        "completeTask must be refused while no buildProject has been called; agent should hit"
            + " iter cap. Saw: "
            + result.outcome());
  }

  @Test
  void completeTask_permitted_when_buildProject_returned_exit_0_within_window() {
    // Iter 1: buildProject succeeds (exit=0). Iter 2: completeTask. Should be permitted.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("buildProject", "{}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"build green\"}")));
    Fixture f = newWorker(seq, 0); // buildExit=0

    WorkerResult result = f.worker.run(gatedReq(10));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "After a successful buildProject (exit=0), completeTask must be permitted within the"
            + " recency window.");
    assertEquals(2, result.iterations());
  }

  @Test
  void completeTask_refused_when_buildProject_returned_nonzero_exit() {
    // Iter 1: buildProject FAILS (exit=1). Iter 2-6: agent retries completeTask.
    // The gate should not count exit=1 as satisfying the requirement — the wording is
    // "buildProject (exit=0)" specifically.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("buildProject", "{}")));
    for (int i = 0; i < 5; i++) {
      seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    }
    Fixture f = newWorker(seq, 1); // buildExit=1 → still failing

    WorkerResult result = f.worker.run(gatedReq(6));

    assertEquals(
        WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED,
        result.outcome(),
        "buildProject with exit=1 (still failing) must NOT satisfy the gate. completeTask"
            + " stays refused until a real exit=0 lands. Saw: "
            + result.outcome());
  }

  @Test
  void recency_window_is_enforced() {
    // Iter 1: buildProject (exit=0). Iters 2-7: readFile (6 unrelated iters, exceeds the
    // BUILD_VERIFICATION_RECENCY_ITERS=5 window). Iter 8: completeTask → should be REFUSED
    // because the build pass was 7 iters ago, outside the 5-iter window.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("buildProject", "{}"))); // iter 1
    for (int i = 0; i < 6; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}"))); // iters 2-7
    }
    for (int i = 0; i < 5; i++) {
      seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}"))); // iters 8+
    }
    Fixture f = newWorker(seq, 0);

    WorkerResult result = f.worker.run(gatedReq(12));

    assertEquals(
        WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED,
        result.outcome(),
        "Stale buildProject (>5 iters ago) must NOT satisfy the gate. Agent must re-run"
            + " buildProject before completeTask is permitted.");
  }

  @Test
  void gate_off_keeps_default_behaviour_completeTask_always_permitted() {
    // requireBuildVerification not set → no gate. Agent calls completeTask immediately, no
    // buildProject ever runs, but completeTask is still permitted (today's behaviour preserved
    // for build mode where the per-task smoke test is the authority instead).
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq, 0);

    WorkerResult result = f.worker.run(ungatedReq(10));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "With requireBuildVerification=false the gate must be inert. completeTask permitted"
            + " on iter 1 even with no buildProject call.");
    assertEquals(1, result.iterations());
  }
}
