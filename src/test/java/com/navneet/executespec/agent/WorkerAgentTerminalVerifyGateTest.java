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
 * RL-2026-06 — pins the terminal-mode enforced-verify gate ({@code
 * WorkerTaskRequest#terminalVerifyGate}). The generic verify-before-done soft nudge only fires when
 * the worker edited but ran NOTHING since its last edit ({@code lastEditIter > lastVerifyIter}); a
 * TRIVIAL shell smoke (e.g. {@code python -c "import run"}) bumps {@code lastVerifyIter} and
 * silently defeats it, so the worker completes after a shallow check. That is the dominant
 * Terminal-Bench miss (full-89 DeepSeek: {@code cancel-async-tasks} done in 6 tools, failed only
 * the SIGINT-cleanup edge the prose calls out).
 *
 * <p>With {@code terminalVerifyGate=true} (and {@code terminalTaskMode=true}), the worker refuses
 * the FIRST {@code completeTask} ONCE — even after a shallow smoke — and asks it to exercise the
 * deliverable against the task's own concrete criterion, then always permits. One-shot ({@code
 * verifyNudged}) so it can never loop.
 */
class WorkerAgentTerminalVerifyGateTest {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  /** Fake tool that always returns Ok — stands in for writeFile / shell / readFile / etc. */
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

  private record Fixture(WorkerAgent worker, LlmRouter router, ToolRegistry registry) {}

  /** Build a worker stubbed to issue the supplied response sequence on consecutive calls. */
  private Fixture newWorker(List<ChatResponse> responseSequence) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));

    lenient().when(registry.find(anyString())).thenAnswer(inv -> fakeTool(inv.getArgument(0)));
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
            null,
            null);
    return new Fixture(worker, router, registry);
  }

  /**
   * Terminal-mode request. {@code verifyGate} toggles the new gate; stagnation disabled so the only
   * thing that can refuse completeTask is the verify gate. maxIter=20 keeps the artifact deadline
   * (iter 21) and forced finalizer (iter 22) clear of these short sequences.
   */
  private static WorkerTaskRequest terminalReq(boolean verifyGate) {
    int maxIter = 20;
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-sonnet-4-6")
        .systemPrompt("system")
        .userPrompt("produce /app/answer")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-tb-verify-gate")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(8192)
        .stagnationWarnAfter(maxIter)
        .stagnationForceAfter(maxIter)
        .terminalTaskMode(true)
        .terminalVerifyGate(verifyGate)
        .build();
  }

  @Test
  void gate_fires_after_shallow_smoke_then_permits() {
    // iter1 writeFile, iter2 shell (a trivial smoke that bumps lastVerifyIter and would defeat the
    // GENERIC nudge), iter3 completeTask → must be REFUSED by the terminal gate, iter4 completeTask
    // → permitted (one-shot). This is the core hole the gate closes.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(
        responseWithCalls(toolCall("writeFile", "{\"path\":\"/app/answer\",\"content\":\"x\"}")));
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"python -c 'import run'\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"verified\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(terminalReq(true));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "terminal verify gate must refuse the first completeTask (despite the shallow smoke) then"
            + " permit the second. Saw: "
            + result.outcome());
    assertEquals(
        4,
        result.iterations(),
        "completeTask at iter 3 must be refused once; completion lands at iter 4.");
  }

  @Test
  void gate_off_lets_premature_completeTask_through_after_smoke() {
    // Same sequence, gate OFF. The generic nudge needs lastEditIter > lastVerifyIter, which the
    // shell smoke (iter2, after the iter1 edit) defeats — so completeTask is permitted at iter3.
    // Documents the exact hole the gate closes AND pins that terminalVerifyGate=false is today's
    // behaviour (no regression from merely adding the field).
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(
        responseWithCalls(toolCall("writeFile", "{\"path\":\"/app/answer\",\"content\":\"x\"}")));
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"python -c 'import run'\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(terminalReq(false));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "With the gate off, a shallow smoke defeats the generic nudge and completeTask is"
            + " permitted immediately (today's behaviour).");
    assertEquals(3, result.iterations(), "completeTask permitted on iter 3, no refusal.");
  }

  @Test
  void gate_does_not_fire_when_nothing_written() {
    // iter1 shell, iter2 completeTask. totalWriteCalls==0, so the verify gate (precondition
    // totalWriteCalls>0) must NOT fire — producing-the-artifact is the deadline/finalizer's job,
    // not the verify gate's. completeTask permitted on iter 2.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"ls /app\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"nothing to do\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(terminalReq(true));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "verify gate must not fire when the worker wrote nothing (totalWriteCalls==0).");
    assertEquals(2, result.iterations());
  }

  @Test
  void gate_is_one_shot_and_never_loops() {
    // iter1 writeFile, then completeTask x5. The gate refuses exactly once (iter2) then permits
    // (iter3). Proves the one-shot property — it can never loop the worker on completeTask, the
    // safety property that matters given the RL-2026-05-061 reasoning-only-stall backfire.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(
        responseWithCalls(toolCall("writeFile", "{\"path\":\"/app/answer\",\"content\":\"x\"}")));
    for (int i = 0; i < 5; i++) {
      seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    }
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(terminalReq(true));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(
        3,
        result.iterations(),
        "exactly one refusal (iter 2) then completion (iter 3) — the gate is one-shot.");
  }
}
