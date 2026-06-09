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
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * RL-2026-06 — pins the terminal-mode deterministic deliverable gate (#2, {@code
 * WorkerTaskRequest#requiredDeliverablePaths}). At {@code completeTask} the worker dispatches ONE
 * container-side {@code shell} existence check for the required path(s); any missing path refuses
 * completion (naming it), bounded to {@link WorkerAgent#DELIVERABLE_GATE_MAX_REFUSALS} then YIELDS
 * (fail-open — never a false FAIL). The gate runs only in terminal + remote mode with a non-empty
 * path list.
 *
 * <p>These tests drive the worker in REMOTE mode (so files are "created" via {@code shell}, never
 * {@code writeFile} → {@code totalWriteCalls==0} → the verify nudge stays inert and the deliverable
 * gate is isolated). The gate's own existence check is distinguished from the worker's own tool
 * calls by the {@code ESMISS} marker its command embeds.
 */
class WorkerAgentDeliverableGateTest {

  private static final String MISSING_ENV =
      "{\"ok\":true,\"content\":\"ESMISS[/app/answer.txt]\\n\"}";
  private static final String PRESENT_ENV = "{\"ok\":true,\"content\":\"\"}";
  private static final String OK_ENV = "{\"ok\":true,\"content\":\"ok\"}";

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private record Fixture(WorkerAgent worker) {}

  /**
   * @param remote whether isRemoteRun is true (the gate only runs in remote mode)
   * @param fileExists whether the gate's existence check reports the deliverable present
   */
  private Fixture newWorker(List<ChatResponse> seq, boolean remote, boolean fileExists) {
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
    // The gate's existence check embeds "ESMISS" in its shell command; the worker's own tool calls
    // do not. Route accordingly.
    lenient()
        .when(registry.dispatchRemote(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String args = inv.getArgument(2);
              if (args != null && args.contains("ESMISS")) {
                return fileExists ? PRESENT_ENV : MISSING_ENV;
              }
              return OK_ENV; // the worker's own shell/etc. tool call
            });

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
            // VBC (V235) — WorkspaceTestRunner; null = verify-before-complete gate off.
            null);
    return new Fixture(worker);
  }

  private static WorkerTaskRequest terminalReq(List<String> requiredPaths) {
    int maxIter = 20;
    return WorkerTaskRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4-flash")
        .systemPrompt("system")
        .userPrompt("produce /app/answer.txt")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-tb-deliverable")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(8192)
        .stagnationWarnAfter(maxIter)
        .stagnationForceAfter(maxIter)
        .terminalTaskMode(true)
        .requiredDeliverablePaths(requiredPaths)
        .build();
  }

  @Test
  void refuses_while_missing_then_yields_after_bounded_refusals() {
    // iter1 shell (creates nothing in this stub), iters 2.. completeTask. Gate reports MISSING
    // every time → refuses twice (iters 2,3) then YIELDS on iter 4 (refusals == MAX) → COMPLETED.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"echo x > /app/answer.txt\"}")));
    for (int i = 0; i < 5; i++) {
      seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    }
    Fixture f = newWorker(seq, true, false);

    WorkerResult result = f.worker.run(terminalReq(List.of("/app/answer.txt")));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "gate must refuse a missing deliverable but eventually YIELD (fail-open). Saw: "
            + result.outcome());
    assertEquals(
        4,
        result.iterations(),
        "refused on iters 2 and 3 (2 refusals == MAX), then yields and completes on iter 4.");
  }

  @Test
  void permits_immediately_when_deliverable_present() {
    // Gate reports the file PRESENT → completeTask permitted on the first try.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"echo x > /app/answer.txt\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq, true, true);

    WorkerResult result = f.worker.run(terminalReq(List.of("/app/answer.txt")));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(2, result.iterations(), "deliverable present → no refusal, completes on iter 2.");
  }

  @Test
  void inert_when_no_required_paths() {
    // No required paths → gate is inert → completeTask permitted immediately.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq, true, false);

    WorkerResult result = f.worker.run(terminalReq(List.of()));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations());
  }

  @Test
  void inert_when_not_remote() {
    // Not a remote run → the gate can't check the container, so it's skipped (fail-safe). Even with
    // a required path and the check wired to report MISSING, completeTask is permitted on iter 1.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq, false, false);

    WorkerResult result = f.worker.run(terminalReq(List.of("/app/answer.txt")));

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations());
  }
}
