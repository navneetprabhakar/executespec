package com.navneet.executespec.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * A5 (audit 2026-05-29) — pins the worker's tool-dispatch ROBUSTNESS paths, the gap the audit named
 * ("LLM-output parsing — malformed/partial JSON, not just happy-path fixtures"). The worker turns a
 * tool call with un-parseable JSON args, or a call to a tool that isn't registered, into an error
 * envelope the model can recover from on the next turn — it must NOT crash the loop. These pin that
 * the loop survives a malformed model response and reaches a clean terminal state.
 */
class WorkerAgentToolDispatchTest {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private static Tool okTool(String name) {
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

  /**
   * @param unknownTool when non-null, registry.find returns null for this tool name.
   */
  private WorkerAgent newWorker(List<ChatResponse> seq, String unknownTool) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));
    lenient()
        .when(costTracker.snapshot(anyString()))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    // Declare the broad stub first, then the specific eq() override so it wins precedence for the
    // unknown-tool name (Mockito: last matching stub wins).
    lenient().when(registry.find(anyString())).thenAnswer(inv -> okTool(inv.getArgument(0)));
    if (unknownTool != null) {
      lenient().when(registry.find(eq(unknownTool))).thenReturn(null);
    }
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());

    ChatResponse first = seq.get(0);
    ChatResponse[] rest = seq.subList(1, seq.size()).toArray(new ChatResponse[0]);
    lenient().when(router.call(any())).thenReturn(first, rest);

    return new WorkerAgent(
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
  }

  private static WorkerTaskRequest req() {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .systemPrompt("system")
        .userPrompt("do thing")
        .context(new ExecutionContext("run-disp", "T1", Path.of("/tmp/ws-dispatch")))
        .maxIterations(40)
        .temperature(0.0)
        .maxTokensPerCall(4096)
        .build();
  }

  @Test
  @DisplayName("malformed tool-call args → error envelope, loop recovers (no crash)")
  void malformed_tool_args_recovers() {
    WorkerAgent worker =
        newWorker(
            List.of(
                responseWithCalls(toolCall("readFile", "{ this is : not valid json")), // malformed
                responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}"))), // recovers
            null);

    WorkerResult result = worker.run(req());

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "Un-parseable tool args must yield an INVALID_ARGS envelope the model recovers from, not a"
            + " crash.");
  }

  @Test
  @DisplayName("call to an unregistered tool → error envelope, loop recovers (no crash)")
  void unknown_tool_recovers() {
    WorkerAgent worker =
        newWorker(
            List.of(
                responseWithCalls(
                    toolCall("bogusTool", "{}")), // not registered → find() returns null
                responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}"))),
            "bogusTool");

    WorkerResult result = worker.run(req());

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "An unknown-tool call must yield an error envelope the model recovers from, not a crash.");
  }

  @Test
  @DisplayName("completeTask before a sibling tool call → sibling paired as SUPERSEDED (C2)")
  void completeTask_pairs_trailing_siblings() {
    // A single assistant turn that emits completeTask AND a trailing readFile. completeTask
    // short-circuits the batch, so the readFile is never executed — C2 must still pair it with a
    // synthetic tool_result so the persisted history has no dangling tool_use.
    WorkerAgent worker =
        newWorker(
            List.of(
                responseWithCalls(
                    toolCall("completeTask", "{\"summary\":\"done\"}"),
                    toolCall("readFile", "{\"path\":\"x\"}"))),
            null);

    WorkerResult result = worker.run(req());

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertTrue(
        result.toolCallLog().stream()
            .anyMatch(s -> s.contains("readFile") && s.contains("SUPERSEDED")),
        "A sibling tool call after completeTask must be paired (SUPERSEDED) so the persisted"
            + " history carries a tool_result for every tool_use the assistant emitted.");
  }
}
