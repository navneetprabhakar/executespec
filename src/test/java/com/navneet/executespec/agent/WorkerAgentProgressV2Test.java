package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * progress-v2 (harness-resolution Phase 1, {@code WorkerTaskRequest#progressV2}, flag {@code
 * worker.progress-v2.enabled}, V255) — pins the evidence-based progress engine:
 *
 * <ul>
 *   <li>novelty-based progress: NEW (tool, args) signatures reset the stagnation counter, so
 *       legitimate multi-file investigation never trips the v1 write-only force;
 *   <li>repetition-based force: the SAME signature failing consecutively terminates the loop even
 *       though v1 thresholds are disabled (the benchmark lane sets warn/force = maxIterations);
 *   <li>text-only stops get bounded tool_choice=required retries instead of the v1 2-strike kill;
 *   <li>OFF ⇒ v1 behavior byte-identical (the off-tests pin that).
 * </ul>
 */
class WorkerAgentProgressV2Test {

  private static AssistantMessage.ToolCall toolCall(String name, String args) {
    return new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, args);
  }

  private static ChatResponse responseWithCalls(AssistantMessage.ToolCall... calls) {
    AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private static ChatResponse textOnly(String text) {
    AssistantMessage msg = AssistantMessage.builder().content(text).build();
    return new ChatResponse(List.of(new Generation(msg)));
  }

  private WorkerAgent newWorker(List<ChatResponse> seq) {
    LlmRouter router = mock(LlmRouter.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ContextStore contextStore = mock(ContextStore.class);
    Metrics metrics = mock(Metrics.class);
    RunCostTracker costTracker = mock(RunCostTracker.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);

    PipelineRun runRow = new PipelineRun();
    runRow.setHardCapUsd(null);
    lenient().when(runRepo.findById(anyString())).thenReturn(Optional.of(runRow));
    lenient().when(registry.isRemoteRun(any())).thenReturn(false);
    // registry.find(name) → null ⇒ dispatchOne returns an {"ok":false,...} error envelope for
    // every non-completeTask tool call — the deterministic "failing tool" for these tests.

    if (!seq.isEmpty()) {
      ChatResponse first = seq.get(0);
      ChatResponse[] rest = seq.subList(1, seq.size()).toArray(new ChatResponse[0]);
      lenient().when(router.call(any())).thenReturn(first, rest);
    }

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
        null,
        null);
  }

  private static WorkerTaskRequest req(boolean progressV2, Integer warnAfter, Integer forceAfter) {
    int maxIter = 30;
    return WorkerTaskRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4-flash")
        .systemPrompt("system")
        .userPrompt("fix the bug")
        .context(new ExecutionContext("run-pv2", "T1", Path.of("/tmp/ws-pv2")))
        .maxIterations(maxIter)
        .temperature(0.0)
        .maxTokensPerCall(8192)
        .stagnationWarnAfter(warnAfter == null ? maxIter : warnAfter)
        .stagnationForceAfter(forceAfter == null ? maxIter : forceAfter)
        .progressV2(progressV2)
        .build();
  }

  // ---------------------------------------------------------------- text-only stops

  @Test
  void v1_kills_after_two_text_only_stops() {
    WorkerAgent w = newWorker(List.of(textOnly("planning..."), textOnly("still planning...")));
    WorkerResult r = w.run(req(false, null, null));
    assertEquals(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, r.outcome());
    assertEquals(2, r.iterations(), "v1: nudge on 1st text-only, terminate on 2nd.");
  }

  @Test
  void v2_retries_text_only_stops_then_completes() {
    // text1 → nudge; text2 → forced retry 1; text3 → forced retry 2; completeTask → done.
    WorkerAgent w =
        newWorker(
            List.of(
                textOnly("planning..."),
                textOnly("more planning..."),
                textOnly("even more planning..."),
                responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}"))));
    WorkerResult r = w.run(req(true, null, null));
    assertEquals(
        WorkerResult.Outcome.COMPLETED,
        r.outcome(),
        "v2 must survive repeated text-only turns and let the model finish.");
    assertEquals(4, r.iterations());
  }

  @Test
  void v2_still_terminates_when_model_refuses_tools_past_the_bound() {
    // nudge + 2 forced retries all produce text → bounded fall-through to v1 termination.
    WorkerAgent w =
        newWorker(
            List.of(textOnly("a"), textOnly("b"), textOnly("c"), textOnly("d"), textOnly("e")));
    WorkerResult r = w.run(req(true, null, null));
    assertEquals(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, r.outcome());
    assertEquals(4, r.iterations(), "1 nudge + 2 forced retries, then terminal on the 4th stop.");
  }

  // ---------------------------------------------------------------- novelty vs repetition

  @Test
  void v1_forces_stagnation_on_novel_investigation() {
    // Six DIFFERENT read-shaped calls (unknown tool → error envelope, still non-write). With
    // v1 thresholds warn=2/force=6, the write-only counter force-terminates during exploration.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"cat file" + i + ".txt\"}")));
    }
    seq.add(textOnly("salvage declines")); // Lever B salvage call → no tool call → null
    WorkerAgent w = newWorker(seq);
    WorkerResult r = w.run(req(false, 2, 6));
    assertEquals(
        WorkerResult.Outcome.COMPLETED_BY_STAGNATION,
        r.outcome(),
        "v1 write-only counter kills novel exploration — the defect progress-v2 fixes.");
  }

  @Test
  void v2_lets_novel_investigation_run_and_complete() {
    // Same six novel calls + same tight thresholds — novelty resets the counter each iter, so
    // the worker survives to its completeTask.
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"cat file" + i + ".txt\"}")));
    }
    seq.add(responseWithCalls(toolCall("completeTask", "{\"summary\":\"done\"}")));
    WorkerAgent w = newWorker(seq);
    WorkerResult r = w.run(req(true, 2, 6));
    assertEquals(WorkerResult.Outcome.COMPLETED, r.outcome());
    assertEquals(7, r.iterations(), "six novel investigation iters + completeTask.");
  }

  @Test
  void v2_forces_on_repeated_identical_failure() {
    // The SAME failing call five times (streak == PROGRESS_V2_REPEAT_FAILURE_FORCE_AT) — a true
    // loop — must terminate even with v1 thresholds disabled (warn/force = maxIter, as the
    // benchmark engine lane sets them).
    List<ChatResponse> seq = new ArrayList<>();
    for (int i = 0; i < WorkerAgent.PROGRESS_V2_REPEAT_FAILURE_FORCE_AT; i++) {
      seq.add(responseWithCalls(toolCall("shell", "{\"command\":\"make build\"}")));
    }
    seq.add(textOnly("salvage declines")); // salvage → null → COMPLETED_BY_STAGNATION
    WorkerAgent w = newWorker(seq);
    WorkerResult r = w.run(req(true, null, null));
    assertEquals(
        WorkerResult.Outcome.COMPLETED_BY_STAGNATION,
        r.outcome(),
        "identical failing signature x" + WorkerAgent.PROGRESS_V2_REPEAT_FAILURE_FORCE_AT);
  }

  // ---------------------------------------------------------------- static helpers

  @Test
  void callSignature_normalizes_whitespace_but_not_content() {
    var a = toolCall("shell", "{\"command\": \"go   test ./...\"}");
    var b = toolCall("shell", "{\"command\": \"go test ./...\"}");
    var c = toolCall("shell", "{\"command\": \"go test ./pkg\"}");
    assertThat(WorkerAgent.callSignature(a)).isEqualTo(WorkerAgent.callSignature(b));
    assertThat(WorkerAgent.callSignature(a)).isNotEqualTo(WorkerAgent.callSignature(c));
  }

  @Test
  void isFailureEnvelope_detects_error_and_nonzero_exit_shapes() {
    assertThat(WorkerAgent.isFailureEnvelope("{\"ok\":false,\"error\":{\"kind\":\"IO\"}}"))
        .isTrue();
    assertThat(WorkerAgent.isFailureEnvelope("{\"ok\":true,\"result\":\"exit=1\\nboom\"}"))
        .isTrue();
    assertThat(WorkerAgent.isFailureEnvelope("{\"ok\":true,\"result\":\"\\\"exit_code\\\": 2\"}"))
        .isTrue();
    assertThat(WorkerAgent.isFailureEnvelope("{\"ok\":true,\"result\":\"exit=0\\nall good\"}"))
        .isFalse();
    assertThat(WorkerAgent.isFailureEnvelope("{\"ok\":true,\"result\":\"wrote 40 chars\"}"))
        .isFalse();
    assertThat(WorkerAgent.isFailureEnvelope(null)).isFalse();
  }

  @Test
  void v2_nudge_drops_the_imperfect_edit_directive() {
    String v2 = WorkerAgent.progressNudgeMessageV2(0);
    assertThat(v2).doesNotContain("imperfect edit");
    assertThat(v2).doesNotContain("STOP reading");
    assertThat(v2).contains("completeTask");
    assertThat(v2).contains("identical failing command");
  }
}
