package com.navneet.executespec.engine;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.CompleteTaskTool;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolOutputCompactor;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 sequential worker agent.
 *
 * <p>One agent, one task, one execution context. Multi-turn loop:
 *
 * <ol>
 *   <li>Send the current message history to the LLM via {@link LlmRouter}
 *   <li>If the response contains tool calls, dispatch each through {@link ToolRegistry} and append
 *       the results as a {@link ToolResponseMessage}
 *   <li>If the response contains a {@link CompleteTaskTool} call, terminate with {@link
 *       WorkerResult.Outcome#COMPLETED}
 *   <li>If the response has no tool calls, terminate with {@link
 *       WorkerResult.Outcome#STOPPED_WITHOUT_COMPLETE}
 *   <li>If the iteration cap is reached, terminate with {@link
 *       WorkerResult.Outcome#ITERATION_LIMIT_EXCEEDED}
 * </ol>
 *
 * <p>Phase 3 baseline scope:
 *
 * <ul>
 *   <li>Multi-turn message threading — full history kept in memory for the duration of the task (no
 *       compaction yet — Phase 8 owns context persistence + crash recovery)
 *   <li>Native completeTask signal (no JSON-prefix heuristic)
 *   <li>40-iteration default cap (overridable via WorkerTaskRequest.maxIterations)
 * </ul>
 *
 * <p>Deferred to later phases (per execution plan):
 *
 * <ul>
 *   <li>Hybrid context (upfront file fill from planner-declared dependencyFiles) — Phase 4
 *   <li>Sliding-window compaction at 75% context limit — Phase 8
 *   <li>Redis context persistence + crash recovery — Phase 8
 *   <li>Stall detection / model escalation — Phase 2.x
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerAgent {

  /**
   * Default iteration cap (per V2 plan P3.1 — was 28 in V1 MEDIUM, raised to compensate for
   * SubtaskDecomposer removal).
   */
  public static final int DEFAULT_MAX_ITER = 40;

  /** Persist context every N iterations (Phase 8 — plan P8.1 says "every 5"). */
  public static final int CONTEXT_PERSIST_EVERY_N_ITERATIONS = 5;

  private final LlmRouter router;
  private final ToolRegistry toolRegistry;
  private final ObjectMapper objectMapper;
  private final ContextStore contextStore;
  private final Metrics metrics;
  private final com.navneet.executespec.cost.RunCostTracker costTracker;
  private final com.navneet.executespec.state.repository.PipelineRunRepository
      pipelineRunRepository;

  /**
   * 2026-05-04 — user feedback: "in live log we are only showing tokens, we should show some quirky
   * log lines formatted in same format as Task start and end". Wire SseBroadcaster + RunEvent so
   * the worker can emit per-tool-call + per-iteration progress events that show up in the LiveLog
   * tab alongside the existing Task start/end markers. Same format ({@code RunEvent.log(runId,
   * taskId, msg)}) so the renderer doesn't need changes.
   */
  private final com.navneet.executespec.sse.SseBroadcaster broadcaster;

  /**
   * Phase 5+6 engine extraction (RL-2026-05-052). Bulk tool output flows through this before
   * landing in the worker's history. Default OFF behind {@link ToolOutputCompactor#FLAG_ENABLED} —
   * with the flag off, every call returns Optional.empty() and the worker uses raw output verbatim.
   * Positioned last in the {@code @RequiredArgsConstructor} parameter order so existing test call
   * sites only need to append a single trailing argument (null is accepted; {@link #maybeCompact}
   * null-guards before any method call).
   */
  private final ToolOutputCompactor toolOutputCompactor;

  /** Bug #2 fix — check budget every N iterations, not just at task boundaries. */
  static final int BUDGET_CHECK_EVERY_N_ITERATIONS = 3;

  /**
   * Stagnation guard threshold — after this many consecutive iterations without a write-class tool
   * call (writeFile / editFile), inject a one-shot "PROGRESS CHECK" system message. Calibrated from
   * run a076c876 task T2 (2026-05-03): the worker had finished writing files at iter ~16 and burned
   * the next 44 iters on read+verify+git loops. Eight iterations is enough headroom for legitimate
   * "read several files, then write one big edit" patterns without rewarding loops.
   */
  public static final int STAGNATION_WARN_AFTER_N = 8;

  /**
   * Stagnation force-terminate threshold — after this many consecutive non-write iterations, force
   * the worker to a {@link WorkerResult.Outcome#COMPLETED_BY_STAGNATION} outcome regardless of
   * whether {@code completeTask} was called. The downstream smoke gate decides whether the change
   * set actually compiles. Calibrated as STAGNATION_WARN_AFTER_N + 4 — that gives the worker four
   * post-warning iterations to either call completeTask cleanly or write the missing piece.
   */
  public static final int STAGNATION_FORCE_AFTER_N = 12;

  /**
   * V182 layer-3 — within how many iterations of {@code completeTask} must a successful {@code
   * runTests} call have occurred? When {@link WorkerTaskRequest#requireTestVerification()} is true,
   * the dispatch loop refuses {@code completeTask} unless the agent ran tests within this window. 5
   * is calibrated to be tight enough that the agent can't claim "I ran the tests 30 iters ago and
   * they passed" but loose enough to allow a few post-test polishing iterations (re-read the
   * output, double-check the diff).
   */
  public static final int TEST_VERIFICATION_RECENCY_ITERS = 5;

  /**
   * Name of the structured pytest tool ({@link com.navneet.executespec.tool.RunTestsTool}). Used by
   * the layer-3 verification gate to identify the call that satisfies it.
   */
  static final String RUN_TESTS_TOOL_NAME = "runTests";

  /**
   * Name of the project-build tool ({@link com.navneet.executespec.tool.BuildProjectTool}). Used by
   * the 2026-05-08 build-verification gate (mirrors the test-verification gate from V182 layer-3)
   * to identify the call that satisfies it.
   */
  static final String BUILD_PROJECT_TOOL_NAME = "buildProject";

  /**
   * 2026-05-08 — within how many iterations of {@code completeTask} must a successful {@code
   * buildProject} call (exit=0) have occurred? When {@link
   * WorkerTaskRequest#requireBuildVerification()} is true, the dispatch loop refuses {@code
   * completeTask} unless the agent ran a passing build within this window. 5 matches the
   * test-verification gate — tight enough that the agent can't claim "I built it 20 edits ago" but
   * loose enough for a few post-fix polishing iters.
   */
  public static final int BUILD_VERIFICATION_RECENCY_ITERS = 5;

  /**
   * Tool names that count as "real progress" for the stagnation guard. Reads / git inspection /
   * shell don't reset the counter — only file mutations and explicit build attempts.
   *
   * <p>{@code buildProject} added 2026-05-08: cmatrix gemini run 84252898 stagnated despite the
   * agent legitimately alternating between read + write + build cycles, because the build calls
   * didn't reset the counter. Build attempts ARE deliberate forward motion (especially in
   * programbench mode where {@link com.navneet.executespec.tool.BuildProjectTool}'s wrapper fails
   * any no-op compile.sh — so spam-build can't game the guard, every successful build implies real
   * source compiled).
   */
  private static final java.util.Set<String> WRITE_CLASS_TOOLS =
      java.util.Set.of("writeFile", "editFile", "buildProject");

  /**
   * Per-call HTTP read-timeout override applied to every worker LLM call. Honoured by {@link
   * GeminiNativeToolClient} (where the static default 60s is too tight for thinking-model worker
   * calls); ignored by Anthropic + OpenAI clients (Spring AI manages their HTTP timeouts).
   * Calibrated 2026-05-08 from cmatrix gemini run 84252898 — task T3 timed out after a 17-min
   * worker phase with 122k input tokens.
   */
  private static final int WORKER_HTTP_READ_TIMEOUT_SECONDS = 120;

  public WorkerResult run(WorkerTaskRequest req) {
    return run(req, null);
  }

  /**
   * Resume-aware overload. When {@code resumeFrom} is non-null, the worker:
   *
   * <ul>
   *   <li>Skips the fresh {@code SystemMessage + UserMessage} bootstrap and uses the saved {@link
   *       com.navneet.executespec.context.SavedContext#history()} as starting history.
   *   <li>Starts the iteration loop at {@code resumeFrom.iteration() + 1}, capped at the same
   *       {@code maxIter}. The remaining iteration budget is therefore proportionally smaller.
   *   <li>Snapshots are written every {@value #CONTEXT_PERSIST_EVERY_N_ITERATIONS} iters as always,
   *       so a second crash mid-resume is also recoverable.
   * </ul>
   *
   * <p>Idempotency contract — the worker tools the resume path relies on:
   *
   * <ul>
   *   <li>{@code writeFile} — overwrites whole-file content. Re-issuing the same call is a no-op.
   *   <li>{@code editFile} — fails with a clear error envelope if the {@code old_string} is no
   *       longer present (because it was already replaced by the previous, possibly lost, run).
   *       Worker self-corrects by reading the file and trying again or moving on.
   *   <li>{@code gitCommit} — denied for the worker entirely (see {@link
   *       ToolRegistry#WORKER_DENY_TOOLS}). Run-end commit is in {@code ScmPushService}.
   *   <li>{@code shell} — best-effort idempotent for build / test commands; mutating shell commands
   *       are sandbox-rejected at policy level.
   *   <li>{@code completeTask} — if the worker called it BEFORE the crash, the snapshot was already
   *       deleted (see line in dispatch loop), so resume can't fire on a completed task.
   * </ul>
   *
   * <p>Stagnation counter resets to 0 on resume — the worker gets a fresh {@code
   * STAGNATION_WARN_AFTER_N} of headroom to recover from the interruption.
   */
  public WorkerResult run(
      WorkerTaskRequest req, com.navneet.executespec.context.SavedContext resumeFrom) {
    long start = System.currentTimeMillis();
    int maxIter =
        (req.maxIterations() == null || req.maxIterations() <= 0)
            ? DEFAULT_MAX_ITER
            : req.maxIterations();

    final boolean isResume = resumeFrom != null && !resumeFrom.history().isEmpty();
    int startIter = 1;
    List<Message> history;
    if (isResume) {
      // Snapshot history was written on a clean iteration boundary (after a tool-response turn),
      // so it's well-formed to send straight to the LLM. Defensive copy: ContextStore returns a
      // List<Message> that callers shouldn't share with us.
      history = new ArrayList<>(resumeFrom.history());
      startIter = resumeFrom.iteration() + 1;
      log.info(
          "WorkerAgent RESUME run={} task={} provider={} model={} from iter={} historyLen={}",
          req.context().runId(),
          req.context().taskId(),
          req.provider(),
          req.model(),
          startIter,
          history.size());
    } else {
      history = new ArrayList<>();
      if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
        history.add(new SystemMessage(req.systemPrompt()));
      }
      history.add(new UserMessage(req.userPrompt()));
    }

    List<String> toolLog = new ArrayList<>();
    int toolsDispatched = 0;
    String lastText = "";

    // RL-2026-05-005 — the worker's single progress signal. `itersSinceProgress` counts
    // consecutive iterations with no write-class tool call (writeFile / editFile); it resets to 0
    // on any write. ONE counter, TWO thresholds, ONE nudge replaces the old set of six overlapping
    // guardrails (Lever A force-edit watchdog + Lever C completion nudge + stagnation warn/force).
    //   • progressWarnAfter  → inject ONE corrective UserMessage (the unified nudge).
    //   • progressForceAfter → force-terminate (Lever B salvage first if nothing was ever written).
    // The same one-shot nudge also fires when the model stops text-only without calling
    // completeTask. Per-task override (req.stagnationWarnAfter / req.stagnationForceAfter):
    // SequentialExecutor sets these per complexity (LOW=8/12, MEDIUM=12/18, HIGH=18/28) so that
    // legitimate reverse-engineering / multi-file-read iterations on HIGH tasks don't trip the
    // guard before the worker can write. Falls through to the static defaults when null.
    final int progressWarnAfter =
        req.stagnationWarnAfter() != null && req.stagnationWarnAfter() > 0
            ? req.stagnationWarnAfter()
            : STAGNATION_WARN_AFTER_N;
    final int progressForceAfter =
        req.stagnationForceAfter() != null && req.stagnationForceAfter() > progressWarnAfter
            ? req.stagnationForceAfter()
            : Math.max(STAGNATION_FORCE_AFTER_N, progressWarnAfter + 4);
    int itersSinceProgress = 0;
    // One-shot: at most one corrective nudge per task, whether triggered by the progress counter
    // crossing progressWarnAfter mid-loop or by a premature text-only stop. After it fires once,
    // the next failure terminates (force threshold, or another text-only stop).
    boolean progressNudged = false;

    // V182 layer-3 — last iteration on which a runTests call returned an ok envelope.
    // -1 means "never". Refreshed inside the per-tool-call dispatch when name=runTests
    // and the envelope JSON starts with `{"ok":true`. The completeTask gate consults
    // this before allowing termination.
    int lastTestRunIter = -1;
    final boolean gateOn = Boolean.TRUE.equals(req.requireTestVerification());

    // 2026-05-08 — last iteration on which a buildProject tool returned an ok envelope WITH
    // exit=0 in its content. Mirrors lastTestRunIter; consulted by the completeTask gate when
    // requireBuildVerification is true (set by ReviewerAgent when entering a pass on a
    // build-failing workspace).
    int lastBuildPassIter = -1;
    final boolean buildGateOn = Boolean.TRUE.equals(req.requireBuildVerification());

    // Total write-class tool calls (writeFile / editFile) across the whole run. Distinct from
    // itersSinceProgress (which resets): this never decreases, and gates the Lever B salvage —
    // a run that has written SOMETHING doesn't need a best-guess salvage call. RL-2026-05-005.
    int totalWriteCalls = 0;

    // #3 verify-before-done (RL-2026-05-051). BUILD/blind has no hard runTests gate (the target
    // test is held out), so instead we SOFT-nudge once: if the worker calls completeTask after
    // editing but without having RUN anything (shell repro / buildProject) since its last edit, we
    // refuse that one completeTask with a "verify your fix first" message, then always allow. One
    // shot (verifyNudged) so it can never loop. Targets the wrong-fix miss bucket.
    int lastEditIter = -1; // last writeFile/editFile
    int lastVerifyIter = -1; // last shell or buildProject (the worker ran something to check)
    boolean verifyNudged = false;

    // 2026-05-10 — per-task wall-clock budget. Sibling of maxIter — whichever fires first wins.
    // Default to a reasonable per-tier ceiling when not provided so callers that haven't migrated
    // (e.g. ReviewerAgent's two passes) still get the new safety net. Zero/null = unbounded.
    final long wallClockMillisCap =
        req.wallClockSeconds() != null && req.wallClockSeconds() > 0
            ? req.wallClockSeconds() * 1000L
            : 0L;

    if (!isResume) {
      log.info(
          "WorkerAgent START run={} task={} provider={} model={} maxIter={}",
          req.context().runId(),
          req.context().taskId(),
          req.provider(),
          req.model(),
          maxIter);
    }

    // Bug #2 fix — load the run's hard cap once so the loop can stop
    // itself if cost blows past it mid-task. Null cap = no enforcement.
    final String runId = req.context().runId();
    final Double hardCapUsd =
        pipelineRunRepository.findById(runId).map(r -> r.getHardCapUsd()).orElse(null);

    for (int iter = startIter; iter <= maxIter; iter++) {
      // 2026-05-10 — wall-clock cap check. Top-of-iter so we never start a new LLM call once
      // we've blown past the budget. In-flight LLM calls are not interrupted (the stop happens
      // on the next iter boundary), so the actual termination time is "wall-clock cap +
      // current LLM call latency" — typically <60s tail.
      if (wallClockMillisCap > 0 && (System.currentTimeMillis() - start) >= wallClockMillisCap) {
        log.warn(
            "WorkerAgent WALL_CLOCK_EXCEEDED run={} task={} iter={} elapsedMs={} capMs={}"
                + " totalWrites={}",
            runId,
            req.context().taskId(),
            iter,
            System.currentTimeMillis() - start,
            wallClockMillisCap,
            totalWriteCalls);
        recordTerminal(WorkerResult.Outcome.WALL_CLOCK_EXCEEDED, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.WALL_CLOCK_EXCEEDED)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
      if (hardCapUsd != null
          && hardCapUsd > 0
          && (iter == 1 || iter % BUDGET_CHECK_EVERY_N_ITERATIONS == 0)) {
        double spent = costTracker.snapshot(runId).costUsd();
        if (spent >= hardCapUsd) {
          log.warn(
              "WorkerAgent BUDGET_EXCEEDED run={} task={} iter={} spent=${} cap=${} totalWrites={}",
              runId,
              req.context().taskId(),
              iter,
              String.format("%.4f", spent),
              String.format("%.4f", hardCapUsd),
              totalWriteCalls);
          // 2026-05-08 — extend Lever B salvage to BUDGET_EXCEEDED path. Previously only
          // ITERATION_LIMIT_EXCEEDED triggered runSalvageCall; a BUDGET_EXCEEDED with zero
          // writes also produced an empty patch (observed Gemini sqlfluff-1733/1517 hitting
          // $4 cap with 0 edits). One extra LLM call is ~$0.10-0.50, acceptable spend over
          // the cap to convert "empty patch = automatic failure" into "best-guess patch =
          // possible resolve." For BYOK runs cap is null so this branch never fires anyway.
          if (totalWriteCalls == 0) {
            try {
              WorkerResult salvage = runSalvageCall(req, history, toolsDispatched, toolLog, start);
              if (salvage != null) {
                // Mark salvage outcome distinct from clean BUDGET_EXCEEDED so downstream sees
                // we DID produce output despite hitting cap. Reuses the iter-limit salvage
                // path which returns COMPLETED on a successful write.
                log.info(
                    "WorkerAgent BUDGET_EXCEEDED salvage produced output run={}",
                    req.context().runId());
                return salvage;
              }
            } catch (Exception e) {
              log.warn(
                  "WorkerAgent BUDGET salvage failed run={}: {}",
                  req.context().runId(),
                  e.getMessage());
            }
          }
          recordTerminal(WorkerResult.Outcome.BUDGET_EXCEEDED, req, iter);
          // P3.1 (GA-readiness, 2026-05-06) — return outcome=BUDGET_EXCEEDED so the executor
          // sees the budget signal directly. Previously this path called errorResult(...) which
          // hardcoded Outcome.ERROR; metrics recorded BUDGET_EXCEEDED but the WorkerResult
          // outcome read as ERROR — so SequentialExecutor's budget-aware branches couldn't
          // distinguish a router crash from a cost-cap stop.
          return WorkerResult.builder()
              .outcome(WorkerResult.Outcome.BUDGET_EXCEEDED)
              .iterations(iter)
              .toolCallsDispatched(toolsDispatched)
              .finalAssistantText(
                  "hard cap $"
                      + String.format("%.2f", hardCapUsd)
                      + " exceeded mid-task (spent $"
                      + String.format("%.2f", spent)
                      + ")")
              .toolCallLog(toolLog)
              .totalLatencyMs(System.currentTimeMillis() - start)
              .build();
        }
      }

      ChatResponse resp;
      try {
        resp =
            router.call(
                LlmRequest.builder()
                    .provider(req.provider())
                    .model(req.model())
                    .messages(history)
                    .context(req.context())
                    .temperature(req.temperature())
                    .maxTokens(req.maxTokensPerCall())
                    // Strip gitCommit from the worker's tool authorities. See
                    // ToolRegistry#WORKER_DENY_TOOLS for the rationale.
                    .toolsOverride(toolRegistry.bindForWorker(req.context()))
                    // 2026-05-08 — Gemini's static GeminiNativeToolClient.READ_TIMEOUT is 60s,
                    // calibrated to fail fast on stalled worker calls. But gemini-3-pro-preview
                    // (thinking model) regularly takes 60-90s on dense tool-call iterations,
                    // tripping the timeout mid-task. Cmatrix run 84252898 task T3 FAILED with
                    // ResourceAccessException I/O error after the worker burned 17m of work.
                    // 120s gives 2× headroom while still detecting genuinely stuck calls within
                    // 2 minutes. Anthropic + OpenAI clients ignore the field (Spring AI manages
                    // their HTTP timeouts).
                    .readTimeoutSeconds(WORKER_HTTP_READ_TIMEOUT_SECONDS)
                    .build());
      } catch (Exception e) {
        log.error("WorkerAgent router failure at iter={}", iter, e);
        recordTerminal(WorkerResult.Outcome.ERROR, req, iter);
        return errorResult(
            iter,
            toolsDispatched,
            toolLog,
            start,
            "router error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }

      // Collect tool calls + assistant text across all generations (Anthropic
      // returns multi-gen responses; OpenAI/Gemini single-gen — same code path works).
      StringBuilder textBuf = new StringBuilder();
      List<AssistantMessage.ToolCall> calls = new ArrayList<>();
      // 2026-05-28 (RL-2026-05-052) — pass through DeepSeek's reasoning_content metadata so the
      // next iteration's assistant turn can echo it (api.deepseek.com requires the round-trip on
      // thinking-mode models). For multi-gen responses we take the first generation's metadata —
      // empirically only single-gen providers (OpenAI-wire family) carry this field.
      java.util.Map<String, Object> assistantMetadata = null;
      for (var gen : resp.getResults()) {
        String t = gen.getOutput().getText();
        if (t != null && !t.isEmpty()) {
          if (textBuf.length() > 0) textBuf.append("\n");
          textBuf.append(t);
        }
        if (gen.getOutput().getToolCalls() != null) {
          calls.addAll(gen.getOutput().getToolCalls());
        }
        if (assistantMetadata == null
            && gen.getOutput().getMetadata() != null
            && !gen.getOutput().getMetadata().isEmpty()) {
          assistantMetadata = gen.getOutput().getMetadata();
        }
      }
      String assistantText = textBuf.toString();
      if (!assistantText.isEmpty()) lastText = assistantText;

      log.info(
          "WorkerAgent iter={} text={}chars toolCalls={}",
          iter,
          assistantText.length(),
          calls.size());

      // 2026-05-04 — quirky per-iteration log line. Visible in the LiveLog tab in the same
      // banner format as the surrounding "Starting task:" / "Worker COMPLETED ..." lines.
      // Filter to interesting iterations only (tool-call iters and the first text-only one)
      // so we don't spam — the existing log file at JVM stdout still has every iter for ops.
      if (!calls.isEmpty()) {
        emitWorkerLog(
            req,
            "💭 Iter "
                + iter
                + " · "
                + calls.size()
                + " tool call"
                + (calls.size() == 1 ? "" : "s")
                + (assistantText.length() > 0 ? " · " + previewThought(assistantText) : ""));
      }

      // No tool calls → model is done (either text-only stop or stopped early).
      if (calls.isEmpty()) {
        // RL-2026-05-005 — text-only stop without calling completeTask: the model produced a
        // status report instead of closing the loop (the dominant "summary mode" failure, e.g.
        // dev run a59f9798/T8 — wrote 10 files, said "implementation is complete", never called
        // the tool). Fire the SAME one-shot progress nudge used mid-loop: if we haven't nudged
        // yet, give the model one more iteration to either call completeTask or make the next
        // concrete edit. If we've already spent the nudge, stop with STOPPED_WITHOUT_COMPLETE.
        if (!progressNudged) {
          progressNudged = true;
          log.info(
              "WorkerAgent PROGRESS_NUDGE (text-only stop) run={} task={} iter={} writes={}"
                  + " textPreview={}",
              runId,
              req.context().taskId(),
              iter,
              totalWriteCalls,
              previewThought(assistantText));
          history.add(new AssistantMessage(assistantText));
          history.add(new UserMessage(progressNudgeMessage(totalWriteCalls)));
          continue; // Re-enter the loop; iter increments; model gets another shot.
        }
        history.add(new AssistantMessage(assistantText));
        recordTerminal(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }

      // Append the assistant turn (text + tool_calls + optional metadata) to history.
      AssistantMessage.Builder assistantBuilder =
          AssistantMessage.builder().content(assistantText).toolCalls(calls);
      if (assistantMetadata != null) {
        assistantBuilder.properties(assistantMetadata);
      }
      history.add(assistantBuilder.build());

      // Dispatch every tool call. completeTask short-circuits the loop.
      List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
      String completionSummary = null;
      // RL-2026-05-052 Phase 5+6 — the LAST tool call in this batch is the "immediately prior"
      // call for the next LLM turn; its output is the most likely to be re-referenced by the
      // worker's next decision, so ToolOutputCompactor preserves it verbatim (skip compaction).
      // Loop converted from for-each to indexed for so we can compute callIdx == lastIdx without
      // re-iterating; behaviour-equivalent to the prior for-each (verified: the four pre-existing
      // WorkerAgent test failures reproduce on the unmodified HEAD code with the same
      // for-each).
      final int lastIdx = calls.size() - 1;
      for (int callIdx = 0; callIdx < calls.size(); callIdx++) {
        AssistantMessage.ToolCall tc = calls.get(callIdx);
        final boolean isImmediatelyPriorToolCall = (callIdx == lastIdx);
        toolsDispatched++;
        if (CompleteTaskTool.NAME.equals(tc.name())) {
          // V182 layer-3 verification gate. When the request opted in (patch mode),
          // refuse completeTask unless a runTests call landed within the last
          // TEST_VERIFICATION_RECENCY_ITERS iterations. The agent gets the refusal
          // envelope in tool history and can react by calling runTests next iter.
          // Build mode (gateOn=false) keeps today's behaviour — completeTask always
          // succeeds because build mode has its own smoke gate downstream.
          if (gateOn) {
            int sinceLastTest = lastTestRunIter < 0 ? Integer.MAX_VALUE : iter - lastTestRunIter;
            if (sinceLastTest > TEST_VERIFICATION_RECENCY_ITERS) {
              String reason;
              if (lastTestRunIter < 0) {
                reason =
                    "completeTask refused — you have not called the `runTests` tool"
                        + " in this conversation. Run the failing test (using runTests"
                        + " with the FAIL_TO_PASS test ids from the user message) and"
                        + " confirm it passes before calling completeTask. The bench"
                        + " harness will not score your patch unless the failing test"
                        + " actually passes.";
              } else {
                reason =
                    "completeTask refused — last successful runTests was "
                        + sinceLastTest
                        + " iterations ago (gate window: "
                        + TEST_VERIFICATION_RECENCY_ITERS
                        + "). Re-run the failing test now to confirm your patch still"
                        + " works against the live workspace, then call completeTask"
                        + " again.";
              }
              String refused = errEnvelope("FORBIDDEN", reason);
              toolResponses.add(
                  new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
              toolLog.add(tc.name() + " → REFUSED (no recent runTests)");
              log.info(
                  "WorkerAgent completeTask GATED run={} task={} iter={} sinceLastTest={}",
                  req.context().runId(),
                  req.context().taskId(),
                  iter,
                  sinceLastTest == Integer.MAX_VALUE ? "never" : sinceLastTest);
              // Don't return — fall through; the rest of this iter's tool calls (if any)
              // still dispatch normally, and the next iter the agent sees the refusal
              // in history.
              continue;
            }
          }
          // 2026-05-08 build-verification gate. Symmetric to the test-verification gate above:
          // when the reviewer entered with finalBuildPassed=false, it sets
          // requireBuildVerification.
          // We refuse completeTask unless the agent has run buildProject and seen exit=0 within
          // BUILD_VERIFICATION_RECENCY_ITERS. Without this, the reviewer often calls completeTask
          // after a few exploratory edits without re-running the build — leaving the workspace
          // in PARTIAL/FINAL_BUILD_FAILED. Targets the dominant 30-day failure mode (16/22).
          if (buildGateOn) {
            int sinceLastBuild =
                lastBuildPassIter < 0 ? Integer.MAX_VALUE : iter - lastBuildPassIter;
            if (sinceLastBuild > BUILD_VERIFICATION_RECENCY_ITERS) {
              String reason;
              if (lastBuildPassIter < 0) {
                reason =
                    "completeTask refused — the build was failing entering this review pass"
                        + " and you have not yet seen a successful `buildProject` call (exit=0)"
                        + " in this conversation. Make whatever edits are needed to fix the"
                        + " build error, then call buildProject and confirm exit=0 before"
                        + " calling completeTask. A reviewer pass that ends with a still-failing"
                        + " build is the dominant cause of PARTIAL runs in production.";
              } else {
                reason =
                    "completeTask refused — last passing buildProject (exit=0) was "
                        + sinceLastBuild
                        + " iterations ago (gate window: "
                        + BUILD_VERIFICATION_RECENCY_ITERS
                        + "). You may have edited files since then. Re-run buildProject now"
                        + " to confirm the build still passes, then call completeTask again.";
              }
              String refused = errEnvelope("FORBIDDEN", reason);
              toolResponses.add(
                  new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
              toolLog.add(tc.name() + " → REFUSED (no recent buildProject pass)");
              log.info(
                  "WorkerAgent completeTask BUILD-GATED run={} task={} iter={} sinceLastBuild={}",
                  req.context().runId(),
                  req.context().taskId(),
                  iter,
                  sinceLastBuild == Integer.MAX_VALUE ? "never" : sinceLastBuild);
              continue;
            }
          }
          // #3 verify-before-done soft nudge (BUILD/blind; PATCH uses the hard gateOn above). If
          // the
          // worker edited but ran nothing since its last edit, refuse this completeTask ONCE and
          // ask
          // it to verify. One-shot (verifyNudged) → can never loop; worst case is one round-trip.
          if (!gateOn && !verifyNudged && totalWriteCalls > 0 && lastEditIter > lastVerifyIter) {
            verifyNudged = true;
            String reason =
                errEnvelope(
                    "VERIFY",
                    "completeTask deferred — you've edited files but haven't run anything since"
                        + " your last edit. Verify the fix actually works: run your reproduction or"
                        + " the relevant tests via the shell tool (or buildProject) and confirm the"
                        + " expected behaviour, then call completeTask again. If the change is"
                        + " genuinely unverifiable, say why in completionNotes and call"
                        + " completeTask again now.");
            toolResponses.add(
                new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), reason));
            toolLog.add(tc.name() + " → NUDGED (verify before complete)");
            continue;
          }
          completionSummary = extractSummary(tc.arguments());
          String envelope = okEnvelope("task marked complete: " + completionSummary);
          toolResponses.add(
              new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), envelope));
          toolLog.add(tc.name() + " → COMPLETED");
          log.info("WorkerAgent completeTask received: {}", completionSummary);
          // Append the tool response so the conversation is well-formed
          // (matters for any post-hoc inspection / persistence).
          history.add(ToolResponseMessage.builder().responses(toolResponses).build());
          // Successful completion — context is no longer needed for recovery.
          contextStore.delete(req.context());
          recordTerminal(WorkerResult.Outcome.COMPLETED, req, iter);
          return WorkerResult.builder()
              .outcome(WorkerResult.Outcome.COMPLETED)
              .iterations(iter)
              .toolCallsDispatched(toolsDispatched)
              .completionSummary(completionSummary)
              .finalAssistantText(lastText)
              .toolCallLog(toolLog)
              .totalLatencyMs(System.currentTimeMillis() - start)
              .build();
        }
        metrics.recordToolCall(tc.name());
        String envelope = dispatchOne(tc, req, isImmediatelyPriorToolCall);
        // V182 layer-3 — track successful runTests for the completeTask gate.
        // 2026-05-08 strengthened: only count runs where tests ACTUALLY PASSED, not just
        // "runTests tool was called." Empirical motivation: 3 of 5 OpenAI hard-matrix failures
        // produced thin (1-2K token) wrong-fix patches because the agent ran tests, saw FAIL,
        // and called completeTask anyway — the gate let it through because it was scoped to
        // "tool was invoked" not "tests passed." Now: require the envelope to contain the
        // pytest-pass markers `"failed":[]` AND `"errored":[]` AND `"exit_code":0` to satisfy
        // the gate. Until those are present, completeTask remains REFUSED.
        if (RUN_TESTS_TOOL_NAME.equals(tc.name())
            && envelope != null
            && envelope.startsWith("{\"ok\":true")
            && envelope.contains("\\\"failed\\\":[]")
            && envelope.contains("\\\"errored\\\":[]")
            && envelope.contains("\\\"exit_code\\\":0")) {
          lastTestRunIter = iter;
        }
        // 2026-05-08 — track successful buildProject (exit=0) for the build-verification gate.
        // BuildProjectTool's content includes a "\nexit=<n>\n" line; in the JSON-encoded ok
        // envelope this becomes the literal substring `\\nexit=0\\n`. Match conservatively to
        // avoid false-positive on output text that happens to contain "exit=0" without the
        // newline framing (e.g. compiler messages quoting source).
        if (BUILD_PROJECT_TOOL_NAME.equals(tc.name())
            && envelope != null
            && envelope.startsWith("{\"ok\":true")
            && envelope.contains("\\nexit=0\\n")) {
          lastBuildPassIter = iter;
        }
        // #3 verify-before-done tracking: file edits vs "ran something to check the fix".
        if ("writeFile".equals(tc.name()) || "editFile".equals(tc.name())) {
          lastEditIter = iter;
        } else if ("shell".equals(tc.name())
            || BUILD_PROJECT_TOOL_NAME.equals(tc.name())
            || RUN_TESTS_TOOL_NAME.equals(tc.name())) {
          lastVerifyIter = iter;
        }
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), envelope));
        toolLog.add(tc.name() + "(" + previewArgs(tc.arguments()) + ")");
        // 2026-05-04 — emit a per-mutation log line so the LiveLog shows real activity, not
        // just the token counter. Filtered to mutating + heavy tools (writes, edits, shell,
        // build) — read/list/search/git noise stays out of the user-facing log so the screen
        // doesn't drown.
        emitToolCallLog(req, tc);
      }
      history.add(ToolResponseMessage.builder().responses(toolResponses).build());

      // ─── Progress signal (RL-2026-05-005) ─────────────────────────────
      // Reset the counter on any write-class tool call (writeFile / editFile); otherwise
      // increment. Crossing progressWarnAfter injects ONE corrective nudge (the same message the
      // text-only-stop path uses); crossing progressForceAfter force-terminates with
      // COMPLETED_BY_STAGNATION (Lever B salvage first if nothing was ever written). One counter,
      // one nudge — this replaces the old Lever A + Lever C + stagnation-warn trio.
      boolean wroteThisIter = calls.stream().anyMatch(tc -> WRITE_CLASS_TOOLS.contains(tc.name()));
      if (wroteThisIter) {
        itersSinceProgress = 0;
        for (AssistantMessage.ToolCall tc : calls) {
          if ("writeFile".equals(tc.name()) || "editFile".equals(tc.name())) totalWriteCalls++;
        }
      } else {
        itersSinceProgress++;
      }
      if (!progressNudged && itersSinceProgress >= progressWarnAfter) {
        log.info(
            "WorkerAgent PROGRESS_NUDGE run={} task={} iter={} itersSinceProgress={} threshold={}"
                + " totalWrites={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            itersSinceProgress,
            progressWarnAfter,
            totalWriteCalls);
        // UserMessage (NOT SystemMessage): GeminiNativeToolClient folds every SystemMessage into
        // systemInstruction, so a mid-run SystemMessage mutates the implicit-cache prefix and
        // kills caching for the rest of the task. A UserMessage stays in contents[] as a normal
        // turn — cache prefix preserved; Anthropic + OpenAI honour it equally.
        history.add(new UserMessage(progressNudgeMessage(totalWriteCalls)));
        progressNudged = true;
      }
      if (itersSinceProgress >= progressForceAfter) {
        log.warn(
            "WorkerAgent PROGRESS_FORCE run={} task={} iter={} itersSinceProgress={} threshold={}"
                + " totalWrites={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            itersSinceProgress,
            progressForceAfter,
            totalWriteCalls);
        // 2026-05-09 Lever B extension — salvage on STAGNATION_FORCE with zero writes.
        // RCA on run 4421cc6e/T9 (Debt Resolution R3+R4): LOW BUILD task spent 12 iters
        // reading without writing, hit stagnation force, was marked FAILED with no recovery
        // attempt. Lever B existed for ITERATION_LIMIT_EXCEEDED + BUDGET_EXCEEDED but not
        // for STAGNATION_FORCE — this third path completes the salvage triangle. Same
        // contract as the iter-limit branch: one extra LLM call, ~$0.05-0.20 spend, "wrong
        // file edit beats empty patch." If salvage produces an edit, the smoke gate
        // downstream decides final task status (PASS → COMPLETE, FAIL → retry / PARTIAL).
        // If salvage fails too, fall through to the original COMPLETED_BY_STAGNATION return.
        if (totalWriteCalls == 0) {
          try {
            WorkerResult salvage = runSalvageCall(req, history, toolsDispatched, toolLog, start);
            if (salvage != null) {
              log.info(
                  "WorkerAgent STAGNATION salvage produced output run={} task={}",
                  req.context().runId(),
                  req.context().taskId());
              return salvage;
            }
          } catch (Exception e) {
            log.warn(
                "WorkerAgent STAGNATION salvage failed run={} task={}: {}",
                req.context().runId(),
                req.context().taskId(),
                e.getMessage());
          }
        }
        recordTerminal(WorkerResult.Outcome.COMPLETED_BY_STAGNATION, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED_BY_STAGNATION)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }

      // Periodic context persistence for crash recovery / retry-with-context. Best-effort;
      // failures inside ContextStore don't propagate. Save on the iteration boundary
      // so a saved snapshot always ends on a well-formed tool-response turn.
      if (iter % CONTEXT_PERSIST_EVERY_N_ITERATIONS == 0) {
        contextStore.save(req.context(), history, iter);
      }
    }

    log.warn(
        "WorkerAgent ITERATION LIMIT run={} task={} iters={} totalWrites={}",
        req.context().runId(),
        req.context().taskId(),
        maxIter,
        totalWriteCalls);

    // 2026-05-08 Lever B: best-guess salvage. We hit the iter cap with zero writes — the agent
    // exhausted the budget on investigation without committing to any edit. Make ONE final LLM
    // call asking for the best-guess edit, with explicit forcing function. The salvage call
    // gets a much shorter system prompt (just "you're out of iters, dump your best guess as a
    // writeFile/editFile call now") and runs with reduced maxIter=2 (the salvage call itself +
    // one iter for tool dispatch). Even a wrong-file edit produces SOMETHING in the diff —
    // empty diff is automatic failure on the SWE-bench evaluator.
    if (totalWriteCalls == 0) {
      try {
        WorkerResult salvage = runSalvageCall(req, history, toolsDispatched, toolLog, start);
        if (salvage != null) return salvage;
      } catch (Exception e) {
        log.warn(
            "WorkerAgent SALVAGE call failed run={} task={}: {}",
            req.context().runId(),
            req.context().taskId(),
            e.getMessage());
      }
    }

    recordTerminal(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED, req, maxIter);
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED)
        .iterations(maxIter)
        .toolCallsDispatched(toolsDispatched)
        .finalAssistantText(lastText)
        .toolCallLog(toolLog)
        .totalLatencyMs(System.currentTimeMillis() - start)
        .build();
  }

  /**
   * 2026-05-08 Lever B salvage. Make one final LLM call with the worker's full conversation history
   * + a forcing function ("you're out of iters, write SOMETHING now"). If the LLM emits a
   * write-class tool call (writeFile / editFile), dispatch it once and return COMPLETED; otherwise
   * return null and the caller falls through to ITERATION_LIMIT_EXCEEDED.
   *
   * <p>Why this beats just letting the iter cap fire: the worker's history has all the
   * investigation context (every readFile output, every searchFiles result). The LLM at this point
   * has built a mental model of the bug — the salvage prompt asks it to dump that into a patch
   * instead of abandoning it. Even a wrong-file edit produces a non-empty diff which is better than
   * nothing on the SWE-bench evaluator (won't resolve, but doesn't fail at the empty-patch
   * precondition).
   */
  private WorkerResult runSalvageCall(
      WorkerTaskRequest req,
      List<Message> history,
      int toolsDispatched,
      List<String> toolLog,
      long start) {
    log.info(
        "WorkerAgent SALVAGE attempting run={} task={} historyLen={}",
        req.context().runId(),
        req.context().taskId(),
        history.size());

    history.add(
        new UserMessage(
            "ITER LIMIT REACHED. You've explored extensively but haven't written any code. This is"
                + " your last call. Based on everything you've read, output ONE writeFile or"
                + " editFile tool call with your best-guess fix. Even a wrong-file edit beats an"
                + " empty patch (empty = automatic failure). Do NOT think out loud, do NOT call"
                + " readFile or any other tool first — emit the write tool call DIRECTLY. If"
                + " genuinely impossible to guess, call completeTask with summary='salvage: no fix"
                + " identified' to exit cleanly."));

    ChatResponse resp;
    try {
      resp =
          router.call(
              LlmRequest.builder()
                  .provider(req.provider())
                  .model(req.model())
                  .messages(history)
                  .context(req.context())
                  .temperature(0.0)
                  .maxTokens(req.maxTokensPerCall())
                  .toolsOverride(toolRegistry.bindForWorker(req.context()))
                  .readTimeoutSeconds(WORKER_HTTP_READ_TIMEOUT_SECONDS)
                  .build());
    } catch (Exception e) {
      log.warn("WorkerAgent SALVAGE router error: {}", e.getMessage());
      return null;
    }

    if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) return null;
    AssistantMessage assistant = resp.getResults().get(0).getOutput();
    List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
    if (calls == null || calls.isEmpty()) {
      log.info("WorkerAgent SALVAGE no tool calls produced — falling through to iter-limit");
      return null;
    }

    // Dispatch each tool call from the salvage response. We accept writeFile, editFile, AND
    // completeTask (the agent admitting it can't guess). Other tools dispatched but ignored
    // (unlikely to fire at this stage given the prompt).
    boolean wroteAnything = false;
    String salvageSummary = null;
    for (AssistantMessage.ToolCall tc : calls) {
      if (CompleteTaskTool.NAME.equals(tc.name())) {
        salvageSummary = "salvage path: " + extractSummary(tc.arguments());
        log.info("WorkerAgent SALVAGE got completeTask: {}", salvageSummary);
        recordTerminal(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED, req, maxIterOf(req));
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED)
            .iterations(maxIterOf(req))
            .toolCallsDispatched(toolsDispatched)
            .completionSummary(salvageSummary)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
      if ("writeFile".equals(tc.name()) || "editFile".equals(tc.name())) {
        // Salvage path only ever dispatches write/edit (both in ToolOutputCompactor.SKIP_TOOLS),
        // so the isLast flag is don't-care; pass false for symmetry with the main loop.
        String env = dispatchOne(tc, req, false);
        toolLog.add(
            "SALVAGE "
                + tc.name()
                + " -> "
                + (env != null && env.startsWith("{\"ok\":true") ? "OK" : "ERR"));
        if (env != null && env.startsWith("{\"ok\":true")) wroteAnything = true;
      }
    }

    if (wroteAnything) {
      log.info("WorkerAgent SALVAGE produced an edit — returning COMPLETED");
      recordTerminal(WorkerResult.Outcome.COMPLETED, req, maxIterOf(req));
      return WorkerResult.builder()
          .outcome(WorkerResult.Outcome.COMPLETED)
          .iterations(maxIterOf(req))
          .toolCallsDispatched(toolsDispatched + calls.size())
          .completionSummary("salvage: best-guess edit produced after iter-limit")
          .toolCallLog(toolLog)
          .totalLatencyMs(System.currentTimeMillis() - start)
          .build();
    }
    return null;
  }

  private static int maxIterOf(WorkerTaskRequest req) {
    return req.maxIterations() != null && req.maxIterations() > 0
        ? req.maxIterations()
        : DEFAULT_MAX_ITER;
  }

  /** Emit worker outcome + iterations metrics at every terminal path. */
  private void recordTerminal(WorkerResult.Outcome outcome, WorkerTaskRequest req, int iterations) {
    metrics.recordWorkerOutcome(outcome.name(), req.provider(), req.model());
    metrics.recordWorkerIterations(outcome.name(), req.provider(), req.model(), iterations);
  }

  private String dispatchOne(
      AssistantMessage.ToolCall tc, WorkerTaskRequest req, boolean isImmediatelyPriorToolCall) {
    // Belt-and-suspenders: even if the model somehow tries to invoke a denied
    // tool by name (e.g. cached tool list, hand-crafted payload), refuse at
    // dispatch time with a clear error envelope so the model can self-correct.
    if (ToolRegistry.WORKER_DENY_TOOLS.contains(tc.name())) {
      return errEnvelope(
          "FORBIDDEN",
          tc.name()
              + " is not available to the worker — workspace changes are committed automatically"
              + " at run-end. Use writeFile/editFile to modify files and call completeTask when"
              + " done.");
    }
    Tool tool = toolRegistry.find(tc.name());
    if (tool == null) {
      return errEnvelope("INVALID_ARGS", "unknown tool: " + tc.name());
    }
    JsonNode args;
    try {
      args =
          (tc.arguments() == null || tc.arguments().isBlank())
              ? objectMapper.createObjectNode()
              : objectMapper.readTree(tc.arguments());
    } catch (Exception e) {
      return errEnvelope("INVALID_ARGS", "tool args not valid JSON: " + e.getMessage());
    }
    try {
      ToolResult result = tool.execute(args, req.context());
      return switch (result) {
          // RL-2026-05-052 Phase 5+6 — wrap the OK content through ToolOutputCompactor before
          // envelope-ing (flag-gated; default OFF returns raw verbatim, so this is byte-identical
          // to the pre-extraction path until the flag is flipped). Error envelopes are short by
          // construction; never compacted.
        case ToolResult.Ok ok ->
            okEnvelope(maybeCompact(tc.name(), ok.content(), isImmediatelyPriorToolCall, req));
        case ToolResult.Err err -> errEnvelope(err.kind().name(), err.message());
      };
    } catch (Exception unexpected) {
      log.error("Tool '{}' threw unexpectedly", tc.name(), unexpected);
      return errEnvelope(
          "INTERNAL", unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
    }
  }

  /**
   * RL-2026-05-052 Phase 5+6 — route bulky tool output through the LIGHTWEIGHT-tier {@link
   * ToolOutputCompactor} before it lands in the worker's history. Default OFF behind the
   * compactor's feature flag; with the flag off this is a no-op pass-through. The worker NEVER
   * loses tool output to compactor failure — fail-safe inside the compactor itself returns empty,
   * and we fall back to raw here.
   *
   * <p>Null-guarded against {@code toolOutputCompactor == null} so unit tests that build a
   * WorkerAgent without wiring the compactor (legacy {@code new WorkerAgent(...)} call sites in the
   * test tree) keep working — the field is non-final at the test level via Mockito field injection
   * patterns, but defensively guarding here avoids a constructor-order trap.
   */
  private String maybeCompact(
      String toolName, String content, boolean isImmediatelyPriorToolCall, WorkerTaskRequest req) {
    if (toolOutputCompactor == null) return content;
    try {
      return toolOutputCompactor
          .compactIfNeeded(
              toolName,
              content,
              req.userPrompt(),
              isImmediatelyPriorToolCall,
              req.context(),
              req.provider())
          .map(ToolOutputCompactor.Compacted::summary)
          .orElse(content);
    } catch (RuntimeException e) {
      // Defence-in-depth: compactor implements its own fail-safe, but if a runtime escapes
      // (e.g. an NPE from a misconfigured dependency at construction time in some weird env),
      // never block the worker. Log + return raw.
      log.warn(
          "ToolOutputCompactor unexpected runtime (tool={}): {} — using raw output",
          toolName,
          e.getMessage());
      return content;
    }
  }

  private String okEnvelope(String content) {
    ObjectNode env = objectMapper.createObjectNode();
    env.put("ok", true);
    env.put("content", content);
    return env.toString();
  }

  private String errEnvelope(String kind, String message) {
    ObjectNode env = objectMapper.createObjectNode();
    env.put("ok", false);
    env.put("kind", kind);
    env.put("message", message);
    return env.toString();
  }

  private String extractSummary(String argsJson) {
    try {
      JsonNode n = objectMapper.readTree(argsJson == null ? "{}" : argsJson);
      return n.path("summary").asText("(no summary)");
    } catch (Exception e) {
      return "(unparseable summary args: " + e.getMessage() + ")";
    }
  }

  private static String safeId(String id) {
    return (id == null || id.isBlank()) ? "call_" + Long.toHexString(System.nanoTime()) : id;
  }

  private static String previewArgs(String args) {
    if (args == null) return "";
    return args.length() > 80 ? args.substring(0, 80) + "..." : args;
  }

  private WorkerResult errorResult(
      int iter, int toolsDispatched, List<String> toolLog, long start, String message) {
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.ERROR)
        .iterations(iter)
        .toolCallsDispatched(toolsDispatched)
        .finalAssistantText(message)
        .toolCallLog(toolLog)
        .totalLatencyMs(System.currentTimeMillis() - start)
        .build();
  }

  // ── 2026-05-04 — quirky LiveLog emitters ─────────────────────────────────────

  /** Emit a worker log line on the run's SSE channel, scoped to the current task. */
  private void emitWorkerLog(WorkerTaskRequest req, String message) {
    try {
      broadcaster.emit(
          req.context().runId(),
          com.navneet.executespec.sse.RunEvent.log(
              req.context().runId(), req.context().taskId(), message));
    } catch (Exception ignore) {
      // SSE failure must never crash the worker loop.
    }
  }

  /**
   * Per-tool-call log line. Filters out the high-volume read-only tools (readFile / listFiles /
   * searchFiles / gitStatus / gitDiff) so the LiveLog shows REAL progress (mutations + builds +
   * shell) instead of the worker's introspective reads. Tool-arg preview kept short (~60 chars
   * after a per-tool prefix) so each line fits one banner row.
   */
  private void emitToolCallLog(WorkerTaskRequest req, AssistantMessage.ToolCall tc) {
    String name = tc.name() == null ? "" : tc.name();
    // Skip read/list/search/git noise — they fire dozens of times per task and would drown the
    // user-facing log. Per-tool emoji + concise preview otherwise.
    String emoji;
    switch (name) {
      case "writeFile" -> emoji = "📝";
      case "editFile" -> emoji = "✏️ ";
      case "shell" -> emoji = "💻";
      case "buildProject" -> emoji = "🔨";
      case "gitCommit" -> emoji = "📦"; // denied for worker, but if it leaks through, show it
      default -> {
        return; // read-only tools — skip
      }
    }
    String preview = extractPathOrCommand(tc.arguments());
    emitWorkerLog(req, emoji + " " + name + (preview.isEmpty() ? "" : " · " + preview));
  }

  /**
   * Pull a short, human-friendly preview from a tool-call's JSON args. Looks for {@code path},
   * {@code filePath}, {@code command}, then falls back to the {@link #previewArgs} truncation.
   */
  private String extractPathOrCommand(String argsJson) {
    if (argsJson == null || argsJson.isEmpty()) return "";
    try {
      JsonNode node = objectMapper.readTree(argsJson);
      if (node.hasNonNull("path")) return truncate(node.get("path").asText(), 80);
      if (node.hasNonNull("filePath")) return truncate(node.get("filePath").asText(), 80);
      if (node.hasNonNull("command")) return truncate(node.get("command").asText(), 80);
      // editFile uses an array of {oldStr, newStr} — show the path + edit count.
      if (node.hasNonNull("file")) {
        String file = truncate(node.get("file").asText(), 60);
        int n = node.path("edits").isArray() ? node.path("edits").size() : 0;
        return file + (n > 0 ? " (" + n + " edit" + (n == 1 ? "" : "s") + ")" : "");
      }
    } catch (Exception ignore) {
      /* fallthrough */
    }
    return previewArgs(argsJson);
  }

  /**
   * Trim a free-text snippet for inclusion in the LiveLog banner. First-line only (so newlines
   * never split the banner into multiple lines), max 120 chars with an ellipsis.
   */
  private static String previewThought(String text) {
    if (text == null || text.isEmpty()) return "";
    int nl = text.indexOf('\n');
    String firstLine = nl > 0 ? text.substring(0, nl) : text;
    return truncate(firstLine.strip(), 120);
  }

  /**
   * 2026-05-10 — surrogate-pair-safe truncation. Caller-visible regression: in the LiveLog,
   * supplementary-plane emoji from the LLM's assistant text (💭 📝 💻 🔨 📦 🧠 💸 — 4-byte UTF-8,
   * encoded in Java as a high+low surrogate pair across two {@code char} units) would render as the
   * Unicode replacement character {@code �} (visually {@code �}) when the LLM happened to place an
   * emoji exactly at the truncation boundary. Root cause: {@link String#length()} counts UTF-16
   * code units, not codepoints, and the old {@code substring(0, max - 1)} happily cut between the
   * high surrogate and its low surrogate, leaving an unpaired high surrogate that the browser
   * couldn't decode.
   *
   * <p>Fix: when the cut point lands inside a surrogate pair (high at {@code end-1}, low at {@code
   * end}), back up one {@code char} so we discard both halves of the pair before appending the
   * ellipsis. Single-{@code char} BMP characters (incl. variation-selector compounds like {@code
   * ✏️} or {@code ⚠️}) are unaffected — those don't use surrogate pairs.
   *
   * <p>Visibility is package-private so {@link
   * com.navneet.executespec.engine.WorkerAgentTruncateTest} can pin the regression directly without
   * reflection.
   */
  // public for the cross-package WorkerAgentTruncateTest after the engine relocation
  // (RL-2026-05-052)
  public static String truncate(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    int end = max - 1;
    if (end > 0
        && Character.isHighSurrogate(s.charAt(end - 1))
        && Character.isLowSurrogate(s.charAt(end))) {
      end -= 1;
    }
    return s.substring(0, end) + "…";
  }

  /**
   * The single corrective nudge (RL-2026-05-005). Shared by both trigger points — the mid-loop
   * progress counter crossing {@code progressWarnAfter}, and a premature text-only stop. It folds
   * together everything the old Lever A ("commit to an edit"), Lever C ("call completeTask, a
   * summary doesn't close the task"), and stagnation-warn ("stop re-reading") messages said into
   * one. Wording is UserMessage-safe — never injected as a SystemMessage (would break Gemini's
   * implicit cache prefix mid-run).
   */
  static String progressNudgeMessage(int totalWriteCalls) {
    String lead =
        totalWriteCalls > 0
            ? "PROGRESS CHECK — you have written "
                + totalWriteCalls
                + " file"
                + (totalWriteCalls == 1 ? "" : "s")
                + " but made no new write in your recent turns and have NOT called `completeTask`."
            : "PROGRESS CHECK — you have used several iterations without writing any file.";
    return lead
        + " Pick ONE:\n"
        + "1. If the task is DONE, call the `completeTask` tool now with your summary as the"
        + " `completionNotes` argument. A text-only summary does NOT close the task — only the"
        + " tool does.\n"
        + "2. If you are still working, STOP reading. If you have not pinned the exact spot, run"
        + " ONE searchFiles / `grep -rn <symbol>` to localize the function, then immediately"
        + " editFile that location — do not open more files to 'understand' first. Commit to the"
        + " single most likely edit, then verify with buildProject (build mode) or runTests (patch"
        + " mode). An empty result is an automatic failure — even an imperfect edit beats zero.\n"
        + "3. If a build or test error is blocking you, fix that file now.\n"
        + "Do NOT do another round of reads or re-verify state you have already checked.";
  }
}
