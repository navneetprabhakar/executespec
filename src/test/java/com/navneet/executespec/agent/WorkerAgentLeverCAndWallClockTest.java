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
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Pins Lever C (completion-gate nudge) + wall-clock cap introduced 2026-05-10 after dev run
 * a59f9798:
 *
 * <ul>
 *   <li>T8 wrote 10 files, emitted "Excellent! The implementation is complete and correct. Let me
 *       create a summary…", and never called {@code completeTask}. Worker terminated as {@code
 *       STOPPED_WITHOUT_COMPLETE} → soft-PARTIAL.
 *   <li>T12 wrote 92 files at 0.92 writes/iter, hit the 100-iter MEDIUM project cap mid-test-suite.
 *       Iter cap killed an actively-progressing worker.
 * </ul>
 *
 * <p>Lever C: when the assistant turn returns no tool calls AND the worker has writes AND iter is
 * past 0.5×maxIter AND the text reads like a summary, inject a one-shot UserMessage telling the
 * model to call completeTask now or list the next concrete edit. Fires at most once per task.
 *
 * <p>Wall-clock cap: sibling of the iter cap. Whichever fires first wins. Protects test-suite-style
 * MEDIUM tasks where the worker is making genuine progress but the iter cap is a "writes/iter"
 * proxy that punishes verbose models.
 */
class WorkerAgentLeverCAndWallClockTest {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private static ChatResponse responseWithText(String text) {
    AssistantMessage msg = AssistantMessage.builder().content(text).toolCalls(List.of()).build();
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

  private record Fixture(WorkerAgent worker, LlmRouter router) {}

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
            mock(com.navneet.executespec.sse.SseBroadcaster.class));
    return new Fixture(worker, router);
  }

  private static WorkerTaskRequest req(int maxIter, Integer wallClockSeconds) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .systemPrompt("system")
        .userPrompt("do thing")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-leverc")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(4096)
        .wallClockSeconds(wallClockSeconds)
        .build();
  }

  // ── Lever C — completion-gate nudge ────────────────────────────────────────────────────────

  @Test
  void looksLikeSummary_recognises_real_T8_assistant_text() {
    String t8 =
        "Excellent! The implementation is complete and correct. Let me create a summary of"
            + " what was done:\n\n## Summary of Changes\n\nI've successfully audited and fixed the"
            + " getContactMedia function...";
    assertTrue(WorkerAgent.looksLikeSummary(t8), "should match the canonical T8 wrap-up shape");
  }

  @Test
  void looksLikeSummary_rejects_short_text() {
    // Below the 12-char min length → quick reject (avoids false positives on stub responses).
    assertFalse(WorkerAgent.looksLikeSummary("done"));
    assertFalse(WorkerAgent.looksLikeSummary(""));
    assertFalse(WorkerAgent.looksLikeSummary(null));
  }

  @Test
  void looksLikeSummary_rejects_text_without_keyword() {
    // Mid-task text without summary phrasing → no match.
    assertFalse(
        WorkerAgent.looksLikeSummary(
            "Reading the file to understand the current implementation pattern."));
    assertFalse(WorkerAgent.looksLikeSummary("Need more context before making changes."));
  }

  @Test
  void lever_c_nudges_when_summary_emitted_after_writes_and_loop_continues() {
    // 6 writes (sets iter ≥ 0.5×maxIter=20 after iter 10), then text-only summary at iter 7,
    // then completeTask at iter 8 (the post-nudge iter). Expected: COMPLETED, iterations=8.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      seq.add(
          responseWithCalls(toolCall("writeFile", "{\"path\":\"f" + i + "\",\"content\":\"x\"}")));
    }
    // iter 7: text-only summary keyword response
    seq.add(
        responseWithText(
            "I've successfully completed the implementation. ## Summary of Changes - everything is"
                + " in place."));
    // iter 8: model accepts the nudge and calls completeTask
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"all done\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(12, null));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "model should land COMPLETED after Lever C nudge");
    assertEquals(8, result.iterations(), "completed at iter 8 (the post-nudge iter)");
  }

  @Test
  void lever_c_does_not_nudge_when_zero_writes() {
    // Pre-condition: text-only summary AT iter 6 (≥ 0.5×maxIter=12) but with ZERO writes — the
    // worker has nothing to claim done. Should fall through to STOPPED_WITHOUT_COMPLETE.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(
        responseWithText(
            "I've successfully completed the analysis. ## Summary - all set, nothing to change."));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(12, null));

    assertEquals(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, result.outcome());
    assertEquals(6, result.iterations());
  }

  @Test
  void lever_c_does_not_nudge_when_too_early() {
    // Summary text emitted at iter 2 (well below 0.5×maxIter=20). Lever C threshold is
    // max(3, round(0.5*maxIter)) = 20 here, so iter 2 falls through immediately.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("writeFile", "{\"path\":\"f\",\"content\":\"x\"}")));
    seq.add(responseWithText("I've completed the work. ## Summary."));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(40, null));

    assertEquals(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, result.outcome());
    assertEquals(2, result.iterations(), "early-iter summary should NOT fire Lever C");
  }

  @Test
  void lever_c_fires_only_once_then_lets_stop_propagate() {
    // 4 writes, then summary at iter 5 (≥ 0.5×maxIter=8 with maxIter=16 → threshold=8; bump to
    // maxIter=8 so threshold=4). Re-emit summary at iter 6 → must terminate not loop forever.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      seq.add(
          responseWithCalls(toolCall("writeFile", "{\"path\":\"f" + i + "\",\"content\":\"x\"}")));
    }
    seq.add(responseWithText("## Summary of Changes — implementation is complete."));
    seq.add(responseWithText("## Summary again — task complete, ready for review."));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(8, null));

    assertEquals(
        WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE,
        result.outcome(),
        "second text-only summary must terminate (no infinite Lever C loop)");
    assertEquals(6, result.iterations(), "exits on the second text-only response");
  }

  // ── Wall-clock cap ─────────────────────────────────────────────────────────────────────────

  @Test
  void wall_clock_cap_terminates_when_exceeded() {
    // 1-second wall-clock cap. Each LLM "call" returns instantly (mock), but we sleep on the
    // tool side to simulate slow work. After ~1.5s elapsed wall-clock the next iter check fires
    // WALL_CLOCK_EXCEEDED.
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
        .when(registry.find(anyString()))
        .thenAnswer(
            inv ->
                new Tool() {
                  @Override
                  public String name() {
                    return inv.getArgument(0);
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
                    try {
                      Thread.sleep(600); // each tool dispatch burns 0.6s wall-clock
                    } catch (InterruptedException ignored) {
                      Thread.currentThread().interrupt();
                    }
                    return new ToolResult.Ok("ok");
                  }
                });
    lenient().when(registry.bindForWorker(any())).thenReturn(List.of());
    // Always return readFile so the worker keeps looping (no completion).
    lenient()
        .when(router.call(any()))
        .thenReturn(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));

    WorkerAgent worker =
        new WorkerAgent(
            router,
            registry,
            new ObjectMapper(),
            contextStore,
            metrics,
            costTracker,
            runRepo,
            mock(com.navneet.executespec.sse.SseBroadcaster.class));

    long t0 = System.currentTimeMillis();
    WorkerResult result =
        worker.run(
            WorkerTaskRequest.builder()
                .provider("anthropic")
                .model("claude-haiku-4-5-20251001")
                .systemPrompt("system")
                .userPrompt("do thing")
                .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-wallclock")))
                .maxIterations(50) // generous so iter cap isn't the trigger
                .temperature(0.0)
                .maxTokensPerCall(4096)
                .wallClockSeconds(1) // trigger on wall-clock
                .build());
    long elapsed = System.currentTimeMillis() - t0;

    assertEquals(WorkerResult.Outcome.WALL_CLOCK_EXCEEDED, result.outcome());
    assertTrue(elapsed >= 1000, "Should have run for at least the wall-clock cap; ran " + elapsed);
    assertTrue(
        elapsed < 5000,
        "Should not run far beyond the cap (allow 1 in-flight iter tail). Ran " + elapsed);
    // The stagnation guard kicks in before wall-clock if we're unlucky on iter pacing — assert
    // we got SOME iterations done first.
    assertTrue(result.iterations() >= 1);
  }

  @Test
  void wall_clock_zero_means_unbounded() {
    // Null wallClockSeconds → no enforcement. Worker terminates by some other reason (here:
    // iter cap).
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(5, null));

    // Without wall-clock, the iter cap (5) wins; stagnation force is 12 by default so iter cap
    // is binding here. ITERATION_LIMIT_EXCEEDED is correct, NOT WALL_CLOCK_EXCEEDED.
    assertTrue(
        result.outcome() == WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED
            || result.outcome() == WorkerResult.Outcome.COMPLETED_BY_STAGNATION,
        "with no wall-clock, terminate by iter cap or stagnation; got " + result.outcome());
  }

  @Test
  void wall_clock_does_not_interrupt_completed_task() {
    // Worker calls completeTask quickly; wall-clock cap is irrelevant.
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("writeFile", "{\"path\":\"f\",\"content\":\"x\"}")));
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(40, 30 * 60)); // 30-min cap

    assertEquals(WorkerResult.Outcome.COMPLETED, result.outcome());
    assertEquals(2, result.iterations());
  }

  /** Test helper: build a UserMessage so the assertion below has a stable type to compare. */
  @SuppressWarnings("unused")
  private static UserMessage userMessageProbe() {
    return new UserMessage("");
  }
}
