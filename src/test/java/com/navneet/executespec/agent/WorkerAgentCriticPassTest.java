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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * RL-2026-06 — pins the terminal-mode critic/reviewer pass (#1, {@code
 * WorkerTaskRequest#criticPass}). The FIRST {@code completeTask} triggers ONE fresh-context
 * acceptance review (a no-tool {@link LlmRouter} call over the task + the deliverable). Concrete
 * gaps refuse completion once; a {@code PASS} verdict or any critic error permits it (fail-open).
 * One-shot so it can't loop.
 *
 * <p>The worker's own main-loop calls also pass an (empty) {@code toolsOverride} in tests, so the
 * critic call is distinguished by its distinctive system prompt ("acceptance reviewer"). The critic
 * call does NOT consume a worker response from the stubbed sequence.
 */
class WorkerAgentCriticPassTest {

  private static final String OK_ENV = "{\"ok\":true,\"content\":\"ok\"}";

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private static ChatResponse responseWithText(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }

  private static boolean isCriticCall(LlmRequest r) {
    return r.messages().stream()
        .map(WorkerAgentCriticPassTest::text)
        .anyMatch(t -> t.contains("acceptance reviewer"));
  }

  private static String text(Message m) {
    try {
      return m.getText() == null ? "" : m.getText();
    } catch (Throwable t) {
      return String.valueOf(m);
    }
  }

  private record Fixture(WorkerAgent worker) {}

  /**
   * @param criticVerdict the critic's response text (e.g. "PASS" or a gap list); ignored if
   *     criticThrows
   * @param criticThrows when true, the critic LlmRouter call throws (tests fail-open)
   */
  private Fixture newWorker(
      List<ChatResponse> workerSeq, String criticVerdict, boolean criticThrows) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));
    lenient().when(registry.isRemoteRun(any())).thenReturn(true);
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());
    lenient().when(registry.dispatchRemote(any(), anyString(), anyString())).thenReturn(OK_ENV);

    AtomicInteger idx = new AtomicInteger(0);
    lenient()
        .when(router.call(any()))
        .thenAnswer(
            inv -> {
              LlmRequest r = inv.getArgument(0);
              if (isCriticCall(r)) {
                if (criticThrows) throw new RuntimeException("critic boom");
                return responseWithText(criticVerdict);
              }
              int i = idx.getAndIncrement();
              return workerSeq.get(Math.min(i, workerSeq.size() - 1));
            });

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
    return new Fixture(worker);
  }

  private static WorkerTaskRequest criticReq() {
    int maxIter = 20;
    return WorkerTaskRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4-flash")
        .systemPrompt("system")
        .userPrompt("implement run_tasks with cleanup on SIGINT")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-tb-critic")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(8192)
        .stagnationWarnAfter(maxIter)
        .stagnationForceAfter(maxIter)
        // NOTE: terminalTaskMode is intentionally NOT set — the critic is common (#1), activated by
        // criticPass alone, not gated on terminal mode. Proves the common-space generalization.
        .criticPass(true)
        // no required paths → the deliverable gate is inert, isolating the critic
        .requiredDeliverablePaths(List.of())
        .build();
  }

  @Test
  void critic_refuses_once_when_gaps_then_permits() {
    // iter1 shell (work), iter2 completeTask → critic finds a gap → REFUSED; iter3 completeTask →
    // critic is one-shot (already ran) → permitted. COMPLETED on iter 3.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"echo work\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"fixed\"}")));
    Fixture f = newWorker(seq, "Gap: the SIGINT cleanup edge is not handled.", false);

    WorkerResult result = f.worker.run(criticReq());

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(3, result.iterations(), "critic refuses iter 2 (gaps), one-shot permits iter 3.");
  }

  @Test
  void critic_pass_permits_immediately() {
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"echo work\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq, "PASS", false);

    WorkerResult result = f.worker.run(criticReq());

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(2, result.iterations(), "PASS verdict → no refusal, completes on iter 2.");
  }

  @Test
  void critic_fails_open_on_error() {
    // The critic LlmRouter call throws → runCriticPass returns null (PASS) → completion
    // permitted.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"echo work\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq, null, true);

    WorkerResult result = f.worker.run(criticReq());

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "critic error must fail-open (permit completion), never block the run.");
    assertEquals(2, result.iterations());
  }

  @Test
  void critic_off_no_review() {
    // criticPass not set → no critic call, completeTask permitted immediately.
    WorkerTaskRequest req =
        WorkerTaskRequest.builder()
            .provider("deepseek")
            .model("deepseek-v4-flash")
            .systemPrompt("system")
            .userPrompt("task")
            .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-tb-critic")))
            .maxIterations(20)
            .temperature(0.0)
            .maxTokensPerCall(8192)
            .stagnationWarnAfter(20)
            .stagnationForceAfter(20)
            .requiredDeliverablePaths(List.of())
            .build();
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq, "Gap: should not be consulted", false);

    WorkerResult result = f.worker.run(req);

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(1, result.iterations(), "critic off → completeTask permitted on iter 1.");
  }

  /** Fake shell tool for LOCAL-mode dispatch — returns the supplied content (e.g. a git diff). */
  private static Tool fakeShellTool(String content) {
    return new Tool() {
      @Override
      public String name() {
        return "shell";
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
        return new ToolResult.Ok(content);
      }
    };
  }

  @Test
  void critic_works_in_local_mode_over_git_diff() {
    // PROVES the common-space generalization: with isRemoteRun=false (a local product run), the
    // critic reads the deliverable via the LOCAL shell tool (git diff) — no remote dispatch — and
    // still fires. iter1 shell, iter2 completeTask → critic finds a gap → REFUSED; iter3 →
    // permitted.
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));
    lenient().when(registry.isRemoteRun(any())).thenReturn(false); // LOCAL
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());
    // Local tool execution: the critic's git-diff read AND the worker's own shell both resolve
    // here.
    lenient()
        .when(registry.find(anyString()))
        .thenReturn(fakeShellTool("diff --git a/run.py b/run.py\n+    finally: cleanup()"));

    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"echo work\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"fixed\"}")));
    AtomicInteger idx = new AtomicInteger(0);
    lenient()
        .when(router.call(any()))
        .thenAnswer(
            inv -> {
              LlmRequest r = inv.getArgument(0);
              if (isCriticCall(r))
                return responseWithText("Gap: cleanup not wired on the error path.");
              return seq.get(Math.min(idx.getAndIncrement(), seq.size() - 1));
            });

    WorkerAgent worker =
        new WorkerAgent(
            router,
            registry,
            new ObjectMapper(),
            mock(ContextStore.class),
            mock(Metrics.class),
            mock(RunCostTracker.class),
            runRepo,
            org.mockito.Mockito.mock(com.navneet.executespec.sse.SseBroadcaster.class),
            null,
            null);

    WorkerResult result =
        worker.run(
            WorkerTaskRequest.builder()
                .provider("anthropic")
                .model("claude-sonnet-4-6")
                .systemPrompt("system")
                .userPrompt("fix the bug with cleanup on error")
                .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-local-critic")))
                .maxIterations(20)
                .temperature(0.0)
                .maxTokensPerCall(8192)
                .stagnationWarnAfter(20)
                .stagnationForceAfter(20)
                .criticPass(true) // common flag — no terminalTaskMode, no remote
                .build());

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(
        3,
        result.iterations(),
        "local-mode critic reads git diff, refuses iter 2 (gap), one-shot permits iter 3.");
  }
}
