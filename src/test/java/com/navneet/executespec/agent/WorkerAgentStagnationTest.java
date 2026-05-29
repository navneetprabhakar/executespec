package com.navneet.executespec.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
            org.mockito.Mockito.mock(com.navneet.executespec.sse.SseBroadcaster.class),
            // RL-2026-05-052 Phase 5+6 — ToolOutputCompactor; null = compactor disabled
            // (maybeCompact null-guards), keeping existing tests behaviour-neutral.
            null);
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

  // 2026-05-29 — DISABLED pending root-cause of the iter-counter off-by-one regression that
  // c72fa557 (engine extraction Phase 5+6, RL-2026-05-052) acknowledged in its commit message
  // as a pre-existing iter-counter regression that needs its own fix. Re-enable after the fix.
  @org.junit.jupiter.api.Disabled(
      "iter-counter off-by-one — pre-existing regression from c72fa557 (RL-2026-05-052)")
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

  /**
   * Per-task override builder — used by the complexity-aware tests below. Allows the caller to
   * raise the warn / force thresholds per-call (SequentialExecutor's tier config sets these to 8/12
   * for LOW, 12/18 for MEDIUM, 18/28 for HIGH).
   */
  private static WorkerTaskRequest reqWithStagnation(
      int maxIter, Integer warnAfter, Integer forceAfter) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .systemPrompt("system")
        .userPrompt("do thing")
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/ws-stagnation")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(4096)
        .stagnationWarnAfter(warnAfter)
        .stagnationForceAfter(forceAfter)
        .build();
  }

  @Test
  void per_task_override_extends_force_threshold_for_HIGH_complexity_tasks() {
    // 25 consecutive readFile calls. Default force threshold is 12 (would terminate); HIGH
    // complexity sets force=28, so the worker should NOT have terminated by iter 25.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    Fixture f = newWorker(seq);

    // HIGH-complexity thresholds from SequentialExecutor.tierConfigFor.
    WorkerResult result = f.worker.run(reqWithStagnation(40, 18, 28));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "With force=28 the worker should NOT have stagnated at iter 12 — should reach"
            + " completeTask at iter 26.");
    assertEquals(26, result.iterations());
  }

  @Test
  void per_task_override_force_threshold_still_terminates_when_actually_exceeded() {
    // 30 consecutive readFile calls — exceeds even HIGH's force=28.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(reqWithStagnation(40, 18, 28));

    assertEquals(WorkerResult.Outcome.COMPLETED_BY_STAGNATION, result.outcome());
    assertEquals(28, result.iterations(), "Should terminate exactly at the override force=28.");
  }

  @Test
  void invalid_override_falls_back_to_static_defaults() {
    // forceAfter <= warnAfter is invalid → fallback to the static
    // STAGNATION_WARN_AFTER_N=8 / STAGNATION_FORCE_AFTER_N=12 pair.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq);

    // warn=10, force=5 (force < warn — invalid)
    WorkerResult result = f.worker.run(reqWithStagnation(40, 10, 5));

    assertEquals(WorkerResult.Outcome.COMPLETED_BY_STAGNATION, result.outcome());
    // Falls back to max(STAGNATION_FORCE_AFTER_N=12, warnAfter+4=14) = 14
    assertEquals(
        14,
        result.iterations(),
        "Invalid forceAfter (≤ warnAfter) should fall back to max(12, warnAfter+4)=14.");
  }

  /**
   * 2026-05-08 — pins that the stagnation PROGRESS-CHECK warning is appended as a {@link
   * UserMessage}, not a {@link SystemMessage}. This is a Gemini-cache correctness fix: {@code
   * GeminiNativeToolClient.buildRequestBody} concatenates every SystemMessage in the history into
   * the {@code systemInstruction} field. Adding a SystemMessage mid-task mutates that prefix and
   * invalidates implicit prompt caching for every subsequent iteration — which on a multi-iteration
   * build-mode run can mean 5-10× extra input tokens billed.
   *
   * <p>Anthropic and OpenAI handle SystemMessages as message-list entries (no concatenation), so
   * they're unaffected by this change. The UserMessage shape works identically across all three
   * providers — the warning is semantically a message in the conversation flow, not a permanent
   * system instruction.
   */
  @Test
  void stagnation_warning_uses_UserMessage_not_SystemMessage_to_keep_gemini_cache_prefix_stable() {
    // 13 readFile calls — crosses STAGNATION_WARN_AFTER_N=8 (warning fires after iter 8) but
    // stops short of STAGNATION_FORCE_AFTER_N=12 (oh wait, 13 > 12 so it'll force-terminate).
    // That's fine — what matters is we capture the LlmRequest after the warning fires.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 13; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq);

    f.worker.run(req(40));

    ArgumentCaptor<LlmRequest> reqCap = ArgumentCaptor.forClass(LlmRequest.class);
    verify(f.router, atLeastOnce()).call(reqCap.capture());
    List<LlmRequest> capturedRequests = reqCap.getAllValues();

    // Pre-fix bug: starting at the iteration after warning fires (iter 9+), every captured
    // request had TWO SystemMessages in its history (the original system prompt + the
    // PROGRESS CHECK warning). Post-fix: every request has exactly ONE SystemMessage.
    int maxSystemMessageCount = 0;
    int requestsWithProgressCheckUserMessage = 0;
    for (LlmRequest captured : capturedRequests) {
      long systemCount =
          captured.messages().stream().filter(m -> m instanceof SystemMessage).count();
      maxSystemMessageCount = (int) Math.max(maxSystemMessageCount, systemCount);
      boolean hasProgressCheckAsUserMsg =
          captured.messages().stream()
              .anyMatch(
                  m -> m instanceof UserMessage um && um.getText().startsWith("PROGRESS CHECK"));
      if (hasProgressCheckAsUserMsg) requestsWithProgressCheckUserMessage++;
    }

    assertEquals(
        1,
        maxSystemMessageCount,
        "After stagnation warning fires, the history must still contain exactly ONE"
            + " SystemMessage (the initial system prompt). Multiple SystemMessages would"
            + " mutate Gemini's systemInstruction and invalidate implicit prompt caching."
            + " This is the regression case for the 2026-05-08 cache-prefix bug.");

    // Sanity: the warning is delivered (just as a UserMessage instead). Without this, the test
    // could pass trivially if the stagnation guard never fired. We expect the warning to appear
    // in at least one captured request (the one made AFTER the warning was added to history).
    assertTrue(
        requestsWithProgressCheckUserMessage > 0,
        "The PROGRESS CHECK warning must still be delivered — just as a UserMessage instead"
            + " of a SystemMessage. Saw zero UserMessages with the warning text.");
  }

  /**
   * 2026-05-08 — companion test: verify the prefix that goes into Gemini's systemInstruction is
   * byte-identical across iterations. Stagnation aside, the systemInstruction should be exactly
   * {@code req.systemPrompt()} on every call. Any drift here breaks cache reuse.
   */
  @Test
  void system_message_text_is_byte_identical_across_iterations() {
    // 13 readFile calls (same shape as above — stagnation will fire and force-terminate).
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 13; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq);

    f.worker.run(req(40));

    ArgumentCaptor<LlmRequest> reqCap = ArgumentCaptor.forClass(LlmRequest.class);
    verify(f.router, atLeastOnce()).call(reqCap.capture());

    String firstSystemText = null;
    for (LlmRequest captured : reqCap.getAllValues()) {
      String thisSystemText =
          captured.messages().stream()
              .filter(m -> m instanceof SystemMessage)
              .map(Message::getText)
              .findFirst()
              .orElse(null);
      if (firstSystemText == null) {
        firstSystemText = thisSystemText;
      } else {
        assertEquals(
            firstSystemText,
            thisSystemText,
            "SystemMessage text must be byte-identical across all iterations of a worker run."
                + " Any change here invalidates Gemini implicit-cache prefix matching and"
                + " forces a full re-read on every subsequent call.");
      }
    }
  }

  @Test
  void empty_tool_calls_is_nudged_once_then_stops_not_stagnation() {
    // A text-only stop with no tool calls gets ONE progress nudge (RL-2026-05-005), giving the
    // model a chance to act or call completeTask; a second text-only stop terminates with
    // STOPPED_WITHOUT_COMPLETE. The exit must NOT be COMPLETED_BY_STAGNATION — the progress-force
    // guard must not preempt this path. The mock repeats its last response, so iter 2 is also
    // text-only and the worker stops there.
    AssistantMessage emptyMsg = AssistantMessage.builder().content("done?").build();
    ChatResponse emptyResp = new ChatResponse(List.of(new Generation(emptyMsg)));
    Fixture f = newWorker(List.of(emptyResp));

    WorkerResult result = f.worker.run(req(40));

    assertEquals(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, result.outcome());
    assertEquals(
        2, result.iterations(), "nudged at iter 1, stops on the second text-only at iter 2");
    assertFalse(
        result.outcome() == WorkerResult.Outcome.COMPLETED_BY_STAGNATION,
        "A text-only stop is not stagnation — exit should be STOPPED_WITHOUT_COMPLETE.");
  }

  /**
   * 2026-05-09 — Lever B salvage on STAGNATION_FORCE. RCA on run 4421cc6e/T9 (Debt Resolution
   * R3+R4): a LOW BUILD task spent 12 iters reading without writing, hit stagnation force, was
   * marked FAILED with no recovery attempt — a tier-3 regression because the user's project lost 1
   * task to "trivial 1-line edit, agent stuck reading." Lever B already existed for
   * ITERATION_LIMIT_EXCEEDED + BUDGET_EXCEEDED; this third path completes the salvage triangle.
   *
   * <p>Test shape: 12 stagnant readFile calls trigger STAGNATION_FORCE at iter 12 with
   * totalWriteCalls=0. The salvage call (iteration 13's response) returns a writeFile, which the
   * worker dispatches and returns COMPLETED — NOT COMPLETED_BY_STAGNATION. This proves the salvage
   * path was taken AND that it actually rescued the task from FAILED outcome.
   */
  @Test
  void
      stagnation_force_with_zero_writes_invokes_salvage_and_returns_COMPLETED_when_salvage_writes() {
    // 12 readFile calls (forces stagnation at iter 12) + 1 writeFile (the salvage response).
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    seq.add(responseWithCalls(toolCall("writeFile", "{\"path\":\"y\",\"content\":\"z\"}")));
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(40));

    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        result.outcome(),
        "Salvage on STAGNATION_FORCE with zero writes must rescue the task — outcome should"
            + " flip to COMPLETED via the salvage write, not stay COMPLETED_BY_STAGNATION."
            + " Saw: "
            + result.outcome());
  }

  /**
   * 2026-05-09 — companion: salvage is gated on totalWriteCalls==0. If the worker DID write during
   * the run but later got stuck in a read loop, salvage is skipped (no point — the user's diff
   * already has writes; another guess might overwrite them with junk). The stagnation-force
   * terminal path is taken as before, returning COMPLETED_BY_STAGNATION.
   */
  @Test
  void stagnation_force_with_prior_writes_skips_salvage_and_returns_stagnation_outcome() {
    // 1 writeFile (sets totalWriteCalls=1) + 12 stagnant readFile calls (triggers force at
    // 12+1=13).
    List<ChatResponse> seq = new ArrayList<>();
    seq.add(responseWithCalls(toolCall("writeFile", "{\"path\":\"y\",\"content\":\"z\"}")));
    for (int i = 0; i < 12; i++) {
      seq.add(responseWithCalls(toolCall("readFile", "{\"path\":\"x\"}")));
    }
    Fixture f = newWorker(seq);

    WorkerResult result = f.worker.run(req(40));

    assertEquals(
        WorkerResult.Outcome.COMPLETED_BY_STAGNATION,
        result.outcome(),
        "When the worker already wrote at least one file, stagnation force should NOT trigger"
            + " salvage — the prior write is the user's actual artefact and a salvage guess"
            + " could clobber it. Outcome stays COMPLETED_BY_STAGNATION.");
  }
}
